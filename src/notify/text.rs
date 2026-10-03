use std::sync::LazyLock;

use regex::Regex;

/// Joins items, replacing the ones that do not fit with "… and N more".
pub(super) fn join_limited<S: AsRef<str>>(items: &[S], separator: &str, max: usize) -> String {
    let mut result = String::new();
    for (index, item) in items.iter().enumerate() {
        let remaining = items.len() - index - 1;
        let candidate_len = char_count(&result)
            + if result.is_empty() {
                0
            } else {
                char_count(separator)
            }
            + char_count(item.as_ref());
        // Reserve room for the suffix unless this is the last item
        let reserve = if remaining == 0 { 0 } else { 20 };

        if candidate_len + reserve > max {
            let suffix = format!(" … and {} more", items.len() - index);
            if result.is_empty() {
                return truncate(&format!("{}{suffix}", item.as_ref()), max);
            }
            result.push_str(&suffix);
            return result;
        }
        if !result.is_empty() {
            result.push_str(separator);
        }
        result.push_str(item.as_ref());
    }
    result
}

pub(super) fn truncate(text: &str, max: usize) -> String {
    if char_count(text) <= max {
        return text.to_string();
    }
    let mut result: String = text.chars().take(max.saturating_sub(1)).collect();
    result.push('…');
    result
}

pub(super) fn char_count(text: &str) -> usize {
    text.chars().count()
}

static HTML_COMMENT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?s)<!--.*?-->").unwrap());
/// Only well-known tags, so generics like `Vec<String>` in the notes survive
static HTML_TAG: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"(?i)</?(?:a|b|br|code|details|div|em|h[1-6]|hr|i|img|kbd|li|ol|p|picture|source|span|strong|sub|summary|sup|ul)\b[^>]*>",
    )
    .unwrap()
});
static MARKDOWN_IMAGE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"!\[[^\]]*\]\([^)]*\)").unwrap());
static HEADING: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^#{1,6}\s+(.*?)(?:\s+#+)?\s*$").unwrap());

/// Turns GitHub flavoured release notes into something that looks decent in Discord: headings
/// become bold text, and HTML and images (which Discord shows verbatim) are dropped.
pub(super) fn simplify_markdown(text: &str) -> String {
    let text = text.replace("\r\n", "\n");
    let text = HTML_COMMENT.replace_all(&text, "");

    let mut lines: Vec<String> = Vec::new();
    let mut in_code = false;
    for line in text.lines() {
        if line.trim_start().starts_with("```") {
            in_code = !in_code;
            lines.push(line.trim_end().to_string());
            continue;
        }
        if in_code {
            lines.push(line.to_string());
            continue;
        }
        let line = HTML_TAG.replace_all(line, "");
        let line = MARKDOWN_IMAGE.replace_all(&line, "");
        let line = line.trim_end();
        let line = match HEADING.captures(line) {
            Some(heading) => format!("**{}**", &heading[1]),
            None => line.to_string(),
        };
        // Collapse runs of blank lines, e.g. left over from removed HTML
        if line.is_empty() && lines.last().is_none_or(|it| it.is_empty()) {
            continue;
        }
        lines.push(line);
    }
    lines.join("\n").trim().to_string()
}

/// Truncates on line boundaries, so links and formatting are not cut in half. Returns whether
/// anything was cut.
pub(super) fn truncate_lines(text: &str, max: usize) -> (String, bool) {
    if char_count(text) <= max {
        return (text.to_string(), false);
    }
    // Room for closing an open code block
    let max = max - 4;
    let mut result = String::new();
    for line in text.lines() {
        if char_count(&result) + char_count(line) + 1 > max {
            break;
        }
        result.push_str(line);
        result.push('\n');
    }
    if result.trim().is_empty() {
        // A single huge line, nothing to be gentle about
        result = truncate(text, max);
    }
    let mut result = result.trim_end().to_string();
    if result.matches("```").count() % 2 == 1 {
        result.push_str("\n```");
    }
    (result, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn join_limited_keeps_everything_that_fits() {
        assert_eq!(join_limited(&["a", "b", "c"], ", ", 100), "a, b, c");
        let joined = join_limited(&["x".repeat(30), "y".repeat(30), "z".repeat(30)], ", ", 70);
        assert!(char_count(&joined) <= 70, "{joined}");
        assert!(joined.ends_with("and 2 more"), "{joined}");
        assert!(char_count(&join_limited(&["q".repeat(500)], ", ", 50)) <= 50);
    }

    #[test]
    fn release_notes_are_cleaned_up() {
        let notes = "## What's new ##\r\n### C#\n<!-- hidden\ncomment -->\n\n\n\n![badge](https://x/y.svg)\n<details><summary>More</summary>\n\n- Use `Vec<String>`\n</details>\n```\n# not a heading\n<b>kept</b>\n```";
        assert_eq!(
            simplify_markdown(notes),
            "**What's new**\n**C#**\n\nMore\n\n- Use `Vec<String>`\n\n```\n# not a heading\n<b>kept</b>\n```"
        );
    }

    #[test]
    fn release_notes_are_cut_on_lines_and_close_code_blocks() {
        let notes = format!(
            "[a link](https://example.com)\n```\n{}",
            "line\n".repeat(100)
        );
        let (cut, truncated) = truncate_lines(&notes, 100);
        assert!(truncated);
        assert!(char_count(&cut) <= 100, "{cut}");
        assert!(
            cut.starts_with("[a link](https://example.com)\n```\nline\n"),
            "{cut}"
        );
        assert!(cut.ends_with("line\n```"), "{cut}");

        assert_eq!(truncate_lines("short", 100), ("short".to_string(), false));
        let (cut, _) = truncate_lines(&"x".repeat(500), 100);
        assert!(char_count(&cut) <= 100);
    }
}
