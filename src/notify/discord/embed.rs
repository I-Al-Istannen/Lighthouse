use std::mem;

use crate::notify::text::{char_count, join_limited, simplify_markdown, truncate, truncate_lines};

use reqwest::Url;
use serenity::all::{CreateEmbed, CreateEmbedAuthor};
pub use serenity::constants::MESSAGE_CODE_LIMIT as MAX_CONTENT;
use serenity::constants::{
    EMBED_MAX_COUNT as MAX_EMBEDS_PER_MESSAGE, EMBED_MAX_LENGTH as MAX_TOTAL,
};

use crate::images::metadata::ImageMetadata;
use crate::updates::model::{ManifestUpdate, TagUpdate};

// https://discord.com/developers/docs/resources/message#embed-object-embed-limits
const MAX_TITLE: usize = 256;
const MAX_DESCRIPTION: usize = 4096;
const MAX_FIELDS: usize = 25;
const MAX_FIELD_NAME: usize = 256;
const MAX_FIELD_VALUE: usize = 1024;
const MAX_AUTHOR: usize = 256;

/// Release notes can be long, keep some room for everything else
const MAX_RELEASE_NOTES: usize = 1500;

const COLOR_DIGEST: u32 = 0xFF6347;
const COLOR_TAG: u32 = 0x00CED1;
const COLOR_ERROR: u32 = 0xB22222;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Embed {
    pub title: String,
    pub url: Option<String>,
    pub description: Option<String>,
    pub color: u32,
    pub fields: Vec<Field>,
    pub author: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Field {
    pub name: String,
    pub value: String,
    pub inline: bool,
}

impl Embed {
    fn field(&mut self, name: &str, value: impl Into<String>, inline: bool) {
        let value = value.into();
        if value.trim().is_empty() {
            return;
        }
        self.fields.push(Field {
            name: name.to_string(),
            value,
            inline,
        });
    }

    /// Clamps every part to Discord's limits.
    pub fn clamped(mut self) -> Self {
        self.title = truncate(&self.title, MAX_TITLE);
        self.description = self.description.map(|it| truncate(&it, MAX_DESCRIPTION));
        self.fields.truncate(MAX_FIELDS);
        for field in &mut self.fields {
            field.name = truncate(&field.name, MAX_FIELD_NAME);
            field.value = truncate(&field.value, MAX_FIELD_VALUE);
        }
        self.author = self.author.map(|it| truncate(&it, MAX_AUTHOR));

        // A single embed can still exceed the per-message total, shorten the description first
        let overflow = self.char_len().saturating_sub(MAX_TOTAL);
        if overflow > 0
            && let Some(description) = &self.description
        {
            let keep = char_count(description).saturating_sub(overflow);
            self.description = Some(truncate(description, keep.max(1)));
        }
        while self.char_len() > MAX_TOTAL && self.fields.pop().is_some() {}
        self
    }

    /// The length Discord counts against the per-message total.
    pub fn char_len(&self) -> usize {
        char_count(&self.title)
            + self.description.as_deref().map_or(0, char_count)
            + self
                .fields
                .iter()
                .map(|it| char_count(&it.name) + char_count(&it.value))
                .sum::<usize>()
            + self.author.as_deref().map_or(0, char_count)
    }

    pub fn to_serenity(&self) -> CreateEmbed {
        let mut embed = CreateEmbed::new()
            .title(&self.title)
            .colour(self.color)
            .fields(
                self.fields
                    .iter()
                    .map(|it| (&it.name, &it.value, it.inline)),
            );
        if let Some(url) = &self.url {
            embed = embed.url(url);
        }
        if let Some(description) = &self.description {
            embed = embed.description(description);
        }
        if let Some(author) = &self.author {
            embed = embed.author(CreateEmbedAuthor::new(author));
        }
        embed
    }
}

/// Splits embeds into messages that respect the embed count and total size limits.
pub fn pack(embeds: Vec<Embed>) -> Vec<Vec<Embed>> {
    let mut messages: Vec<Vec<Embed>> = Vec::new();
    let mut current: Vec<Embed> = Vec::new();
    let mut current_len = 0;

    for embed in embeds {
        let embed = embed.clamped();
        let len = embed.char_len();
        if !current.is_empty()
            && (current.len() == MAX_EMBEDS_PER_MESSAGE || current_len + len > MAX_TOTAL)
        {
            messages.push(mem::take(&mut current));
            current_len = 0;
        }
        current_len += len;
        current.push(embed);
    }
    if !current.is_empty() {
        messages.push(current);
    }
    messages
}

pub fn digest_embed(update: &ManifestUpdate, hostname: Option<&str>) -> Embed {
    let empty_metadata = ImageMetadata::default();
    let metadata = update.metadata.as_ref().unwrap_or(&empty_metadata);
    let mut embed = base_embed(update.base.friendly(), COLOR_DIGEST, hostname);
    embed.url = update
        .base
        .web_url()
        .or_else(|| metadata.source.as_ref().map(|it| it.url.clone()));
    embed.description = Some(
        release_description(metadata)
            .unwrap_or_else(|| format!("A new image was pushed for `{}`.", update.base.tag)),
    );

    let containers: Vec<&str> = update.containers().map(|it| it.name.as_str()).collect();
    embed.field(
        "Containers",
        join_limited(&containers, ", ", MAX_FIELD_VALUE),
        true,
    );

    let images: Vec<String> = update
        .affected
        .iter()
        .flat_map(|it| it.display_names())
        .collect();
    embed.field(
        "Local images",
        join_limited(&images, ", ", MAX_FIELD_VALUE),
        true,
    );

    add_metadata_fields(&mut embed, metadata, None);
    embed.field(
        "New digest",
        format!("`{}`", short_digest(update.remote_digest.as_str())),
        false,
    );
    embed
}

pub fn tag_embed(update: &TagUpdate, hostname: Option<&str>) -> Embed {
    let empty_metadata = ImageMetadata::default();
    let metadata = update.metadata.as_ref().unwrap_or(&empty_metadata);
    let mut embed = base_embed(update.image.friendly_name(), COLOR_TAG, hostname);
    embed.url = update
        .image
        .web_url()
        .or_else(|| metadata.source.as_ref().map(|it| it.url.clone()));

    let mut description = format!("**`{}` → `{}`**", update.image.tag, update.new_tag);
    if let Some(release) = release_description(metadata) {
        description.push_str("\n\n");
        description.push_str(&release);
    }
    embed.description = Some(description);

    let containers: Vec<&str> = update
        .containers
        .iter()
        .map(|it| it.name.as_str())
        .collect();
    embed.field(
        "Containers",
        join_limited(&containers, ", ", MAX_FIELD_VALUE),
        false,
    );
    add_metadata_fields(&mut embed, metadata, Some(&update.new_tag));
    embed
}

pub fn error_embed(error: &str, hostname: Option<&str>) -> Embed {
    let mut embed = base_embed(
        "Error while checking for updates".into(),
        COLOR_ERROR,
        hostname,
    );
    // Leave room for the code fence
    embed.description = Some(format!(
        "```\n{}\n```",
        truncate(error, MAX_DESCRIPTION - 10)
    ));
    embed
}

fn base_embed(title: String, color: u32, hostname: Option<&str>) -> Embed {
    Embed {
        title,
        color,
        author: hostname.map(str::to_string),
        ..Default::default()
    }
}

/// `sha256:` and the first 12 hex digits, like docker shortens ids.
fn short_digest(digest: &str) -> String {
    match digest.split_once(':') {
        Some((algorithm, hex)) => format!("{algorithm}:{}", &hex[..hex.len().min(12)]),
        None => digest.chars().take(12).collect(),
    }
}

fn release_description(metadata: &ImageMetadata) -> Option<String> {
    let release = metadata.release.as_ref()?;
    let mut description = format!("**Release [{}]({})**", release.name, release.url);
    if let Some(body) = &release.body {
        let (notes, truncated) = truncate_lines(&simplify_markdown(body), MAX_RELEASE_NOTES);
        description.push_str("\n\n");
        description.push_str(&notes);
        if truncated {
            description.push_str(&format!("\n\n[… read more]({})", release.url));
        }
    }
    Some(description)
}

/// `skip_version` hides the version label if it just repeats this tag.
fn add_metadata_fields(embed: &mut Embed, metadata: &ImageMetadata, skip_version: Option<&str>) {
    if let Some(created) = metadata.created {
        let mut value = format!("<t:{}:R>", created.as_second());
        if let Some(user) = &metadata.updated_by {
            value.push_str(&format!(" by **{user}**"));
        }
        embed.field("Updated", value, true);
    }
    if let Some(version) = &metadata.version {
        let same = |a: &str, b: &str| a.trim_start_matches('v') == b.trim_start_matches('v');
        if !skip_version.is_some_and(|tag| same(tag, version)) {
            embed.field("Version", format!("`{version}`"), true);
        }
    }
    if let Some(source) = &metadata.source {
        let mut value = format!(
            "[{}]({})",
            source.path.as_deref().unwrap_or(&source.url),
            source.url
        );
        if let (Some(revision), Some(url)) = (&metadata.revision, metadata.revision_url()) {
            let short: String = revision.chars().take(7).collect();
            value.push_str(&format!(" @ [`{short}`]({url})"));
        }
        embed.field("Source", value, true);
    } else if let Some(homepage) = &metadata.homepage {
        let label = Url::parse(homepage).ok().and_then(|it| {
            it.host_str()
                .map(|host| host.trim_start_matches("www.").to_string())
        });
        let value = match label {
            Some(label) => format!("[{label}]({homepage})"),
            None => homepage.clone(),
        };
        embed.field("Homepage", value, true);
    }
}

/// `Hey, <mention> <text>`, with `{IMAGES}` in the text replaced by the given summary.
pub fn mention_content(
    mention: &str,
    text: Option<&str>,
    default_text: &str,
    images: &str,
) -> String {
    let text = text.unwrap_or(default_text).replace("{IMAGES}", images);
    truncate(&format!("Hey, {mention} {text}"), MAX_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::images::reference::ImageRef;
    use crate::updates::model::{AffectedImage, ContainerRef};

    fn update(containers: usize) -> ManifestUpdate {
        ManifestUpdate {
            base: ImageRef::parse("nginx:stable").unwrap(),
            remote_digest: format!("sha256:{}", "a".repeat(64)).into(),
            affected: vec![AffectedImage {
                image_id: "sha256:1".into(),
                repo_tags: vec!["website:latest".into()],
                containers: (0..containers)
                    .map(|i| ContainerRef {
                        id: i.to_string(),
                        name: format!("a-rather-long-container-name-{i}"),
                        is_lighthouse: false,
                    })
                    .collect(),
            }],
            metadata: None,
        }
    }

    #[test]
    fn huge_container_lists_fit_into_fields() {
        let embed = digest_embed(&update(500), Some("host")).clamped();
        let containers = &embed.fields[0];
        assert!(char_count(&containers.value) <= MAX_FIELD_VALUE);
        assert!(containers.value.ends_with("more"), "{}", containers.value);
    }

    #[test]
    fn packs_by_count_and_size() {
        let small: Vec<Embed> = (0..23).map(|_| digest_embed(&update(1), None)).collect();
        let sizes: Vec<usize> = pack(small).iter().map(Vec::len).collect();
        assert_eq!(sizes, vec![10, 10, 3]);

        let big: Vec<Embed> = (0..5)
            .map(|_| Embed {
                title: "t".into(),
                description: Some("d".repeat(4000)),
                ..Default::default()
            })
            .collect();
        for message in pack(big) {
            assert!(message.iter().map(Embed::char_len).sum::<usize>() <= MAX_TOTAL);
        }
    }

    #[test]
    fn clamps_oversized_parts() {
        let embed = Embed {
            title: "t".repeat(1000),
            description: Some("d".repeat(10_000)),
            fields: (0..40)
                .map(|_| Field {
                    name: "n".into(),
                    value: "v".repeat(5000),
                    inline: false,
                })
                .collect(),
            ..Default::default()
        }
        .clamped();
        assert_eq!(char_count(&embed.title), MAX_TITLE);
        // Fields are clamped individually, then dropped until the embed fits a message
        assert!(!embed.fields.is_empty() && embed.fields.len() <= MAX_FIELDS);
        assert!(
            embed
                .fields
                .iter()
                .all(|it| char_count(&it.value) <= MAX_FIELD_VALUE)
        );
        assert!(embed.char_len() <= MAX_TOTAL);
    }

    #[test]
    fn digests_are_shortened() {
        let embed = digest_embed(&update(1), None);
        let digest = embed
            .fields
            .iter()
            .find(|it| it.name == "New digest")
            .unwrap();
        assert_eq!(digest.value, "`sha256:aaaaaaaaaaaa`");
    }

    #[test]
    fn mention_content_is_bounded() {
        let content = mention_content("<@1>", Some("Updates: {IMAGES}"), "", &"img ".repeat(1000));
        assert!(content.starts_with("Hey, <@1> Updates: img"));
        assert!(char_count(&content) <= MAX_CONTENT);
    }
}
