use std::cmp::Ordering;
use std::collections::HashMap;
use std::sync::LazyLock;

use regex::Regex;
use rootcause::prelude::*;

pub const LABEL_STRATEGY: &str = "lighthouse.tag-check.strategy";
pub const LABEL_KEEP: &str = "lighthouse.tag-check.keep";
pub const LABEL_IGNORE: &str = "lighthouse.tag-check.ignore";
pub const LABEL_MAX_BUMP: &str = "lighthouse.tag-check.max-bump";

static DOCKER_TAG: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?P<prefix>[^0-9]*?)(?P<release>[0-9]+(?:\.[0-9]+)*)(?P<suffix>.*)$").unwrap()
});

/// A parsed tag. Only versions of the same [`Shape`] are comparable.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Version {
    shape: Shape,
    release: Vec<u64>,
}

/// Tags like `1.2-alpine` and `1.3-alpine` share a shape, `1.3` or `1.3.0-alpine` do not: they are
/// different variants (or precisions) of the image you probably do not want to switch to.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Shape {
    Docker {
        prefix: String,
        suffix: String,
        components: usize,
    },
    Regex,
}

impl Version {
    fn compare(&self, other: &Version) -> Option<Ordering> {
        (self.shape == other.shape).then(|| self.release.cmp(&other.release))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum MaxBump {
    Patch,
    Minor,
    Major,
}

#[derive(Debug)]
pub enum Strategy {
    /// Docker-style versions: numeric release with optional prefix/suffix, e.g. `v1.2.3-alpine`
    Semver,
    /// Named groups `major` and optionally `minor` and `patch`
    Regex(Regex),
}

impl Strategy {
    pub fn parse(raw: &str) -> Result<Self, Report> {
        if raw == "semver" {
            return Ok(Self::Semver);
        }
        let Some(pattern) = raw.strip_prefix("regex:") else {
            bail!("Unknown tag check strategy '{raw}', expected 'semver' or 'regex:<pattern>'");
        };
        let regex = Regex::new(&format!("^(?:{pattern})$"))
            .context_with(|| format!("Invalid regex in tag check strategy '{raw}'"))?;
        if !regex.capture_names().flatten().any(|it| it == "major") {
            bail!("Regex tag check strategy '{raw}' has no named group 'major'");
        }
        Ok(Self::Regex(regex))
    }

    pub fn parse_tag(&self, tag: &str) -> Option<Version> {
        match self {
            Strategy::Semver => {
                let captures = DOCKER_TAG.captures(tag)?;
                let release: Vec<u64> = captures["release"]
                    .split('.')
                    .map(|it| it.parse().ok())
                    .collect::<Option<_>>()?;
                Some(Version {
                    shape: Shape::Docker {
                        prefix: captures["prefix"].to_string(),
                        suffix: captures["suffix"].to_string(),
                        components: release.len(),
                    },
                    release,
                })
            }
            Strategy::Regex(regex) => {
                let captures = regex.captures(tag)?;
                let number = |name: &str| match captures.name(name) {
                    Some(it) => it.as_str().parse::<u64>().ok(),
                    None => Some(0),
                };
                Some(Version {
                    shape: Shape::Regex,
                    release: vec![number("major")?, number("minor")?, number("patch")?],
                })
            }
        }
    }
}

/// How to pick tag updates for one container, configured through its labels.
#[derive(Debug)]
pub struct TagPolicy {
    pub strategy: Strategy,
    keep: Option<Regex>,
    ignore: Option<Regex>,
    max_bump: MaxBump,
}

impl TagPolicy {
    /// `Ok(None)` if the container does not opt into tag checks.
    pub fn from_labels(labels: &HashMap<String, String>) -> Result<Option<Self>, Report> {
        let Some(strategy) = labels.get(LABEL_STRATEGY) else {
            return Ok(None);
        };
        let full_match = |key: &str| -> Result<Option<Regex>, Report> {
            labels
                .get(key)
                .map(|it| {
                    Ok(Regex::new(&format!("^(?:{it})$"))
                        .context_with(|| format!("Invalid regex in label '{key}'"))?)
                })
                .transpose()
        };
        let max_bump = match labels.get(LABEL_MAX_BUMP).map(|it| it.trim()) {
            None | Some("major") => MaxBump::Major,
            Some("minor") => MaxBump::Minor,
            Some("patch") => MaxBump::Patch,
            Some(other) => {
                bail!("Invalid '{LABEL_MAX_BUMP}' value '{other}', expected major, minor or patch")
            }
        };

        Ok(Some(Self {
            strategy: Strategy::parse(strategy)?,
            keep: full_match(LABEL_KEEP)?,
            ignore: full_match(LABEL_IGNORE)?,
            max_bump,
        }))
    }

    /// The newest acceptable tag that is newer than `current`, if any.
    pub fn newest<'a>(
        &self,
        current: &str,
        available: impl IntoIterator<Item = &'a String>,
    ) -> Result<Option<&'a str>, Report> {
        let Some(current_version) = self.strategy.parse_tag(current) else {
            bail!(
                "Current tag '{current}' can not be parsed with {:?}",
                self.strategy
            );
        };

        let best = available
            .into_iter()
            .filter(|tag| self.keep.as_ref().is_none_or(|it| it.is_match(tag)))
            .filter(|tag| self.ignore.as_ref().is_none_or(|it| !it.is_match(tag)))
            .filter_map(|tag| Some((self.strategy.parse_tag(tag)?, tag.as_str())))
            .filter(|(version, _)| {
                current_version.compare(version) == Some(Ordering::Less)
                    && self.within_max_bump(&current_version, version)
            })
            .max_by(|(a, a_tag), (b, b_tag)| {
                a.release.cmp(&b.release).then_with(|| a_tag.cmp(b_tag))
            });

        Ok(best.map(|(_, tag)| tag))
    }

    fn within_max_bump(&self, current: &Version, candidate: &Version) -> bool {
        let fixed_components = match self.max_bump {
            MaxBump::Major => 0,
            MaxBump::Minor => 1,
            MaxBump::Patch => 2,
        };
        current
            .release
            .iter()
            .zip(&candidate.release)
            .take(fixed_components)
            .all(|(a, b)| a == b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy(labels: &[(&str, &str)]) -> TagPolicy {
        let labels = labels
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        TagPolicy::from_labels(&labels).unwrap().unwrap()
    }

    fn tags(raw: &[&str]) -> Vec<String> {
        raw.iter().map(|it| it.to_string()).collect()
    }

    #[test]
    fn semver_stays_within_variant_and_precision() {
        let policy = policy(&[(LABEL_STRATEGY, "semver")]);
        let available = tags(&[
            "1.25-alpine",
            "1.27-alpine",
            "1.28",
            "1.28.0-alpine",
            "1.29-alpine-slim",
            "latest",
            "alpine",
        ]);

        assert_eq!(
            policy.newest("1.25-alpine", &available).unwrap(),
            Some("1.27-alpine")
        );
        assert_eq!(policy.newest("1.25", &available).unwrap(), Some("1.28"));
        assert_eq!(policy.newest("1.27-alpine", &available).unwrap(), None);
    }

    #[test]
    fn semver_compares_numerically_and_keeps_prefix() {
        let policy = policy(&[(LABEL_STRATEGY, "semver")]);
        let available = tags(&["v1.9.0", "v1.10.0", "1.11.0", "v2.0.0-rc1"]);
        assert_eq!(
            policy.newest("v1.2.0", &available).unwrap(),
            Some("v1.10.0")
        );
    }

    #[test]
    fn rejects_unparseable_current_tag() {
        let policy = policy(&[(LABEL_STRATEGY, "semver")]);
        assert!(policy.newest("latest", &tags(&["1.0"])).is_err());
    }

    #[test]
    fn keep_and_ignore_filter_candidates() {
        let policy = policy(&[
            (LABEL_STRATEGY, "semver"),
            (LABEL_KEEP, r"1\..*"),
            (LABEL_IGNORE, r".*\.9"),
        ]);
        let available = tags(&["1.8", "1.9", "2.0"]);
        assert_eq!(policy.newest("1.7", &available).unwrap(), Some("1.8"));
    }

    #[test]
    fn max_bump_limits_updates() {
        let available = tags(&["1.2.4", "1.3.0", "2.0.0"]);
        let patch = policy(&[(LABEL_STRATEGY, "semver"), (LABEL_MAX_BUMP, "patch")]);
        let minor = policy(&[(LABEL_STRATEGY, "semver"), (LABEL_MAX_BUMP, "minor")]);
        let major = policy(&[(LABEL_STRATEGY, "semver")]);

        assert_eq!(patch.newest("1.2.3", &available).unwrap(), Some("1.2.4"));
        assert_eq!(minor.newest("1.2.3", &available).unwrap(), Some("1.3.0"));
        assert_eq!(major.newest("1.2.3", &available).unwrap(), Some("2.0.0"));
    }

    #[test]
    fn regex_strategy_uses_named_groups() {
        let policy = policy(&[(
            LABEL_STRATEGY,
            r"regex:release-(?<major>\d+)\.(?<minor>\d+)(?:-(?<build>.+))?",
        )]);
        let available = tags(&["release-1.9", "release-1.10-b2", "release-x", "1.11"]);
        assert_eq!(
            policy.newest("release-1.2", &available).unwrap(),
            Some("release-1.10-b2")
        );
    }

    #[test]
    fn invalid_labels_are_errors() {
        let labels =
            |strategy: &str| HashMap::from([(LABEL_STRATEGY.to_string(), strategy.to_string())]);
        assert!(TagPolicy::from_labels(&labels("calver")).is_err());
        assert!(TagPolicy::from_labels(&labels("regex:(")).is_err());
        assert!(TagPolicy::from_labels(&labels(r"regex:\d+")).is_err());
        assert!(TagPolicy::from_labels(&HashMap::new()).unwrap().is_none());
    }
}
