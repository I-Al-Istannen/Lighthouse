package de.ialistannen.lighthouse.versioning;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.semver4j.Semver;

public sealed interface VersionParser {

  /**
   * Parses a version string into a Semver object.
   *
   * @param versionString the version string to parse
   * @return the parsed Semver object
   * @throws IllegalArgumentException if the version string is invalid
   */
  Semver parse(String versionString);

  /**
   * Parses a version parser from its string representation.
   *
   * @param parser the string representation of the parser
   * @return The version parser
   * @throws IllegalArgumentException if the parser type is unknown or invalid
   */
  static VersionParser fromString(String parser) {
    if (parser.equals("semver")) {
      return new SemverVersionParser();
    }
    if (!parser.startsWith("regex:")) {
      throw new IllegalArgumentException("Unknown parser type: " + parser);
    }
    var pattern = parser.substring("regex:".length());
    try {
      var compiled = Pattern.compile(pattern);
      return new RegexVersionParser(compiled);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + pattern, e);
    }
  }

  record RegexVersionParser(Pattern regex) implements VersionParser {

    @Override
    public Semver parse(String versionString) {
      var matcher = regex().matcher(versionString);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Version string does not match the expected format");
      }
      var majorGroup = VersionParser.intOrError(matcher.group("major"));
      var minorGroup = VersionParser.intOrError(matcher.group("minor"));
      var patchGroup = VersionParser.intOrError(matcher.group("patch"));
      var buildGroup = matcher.group("build");

      return new Semver.Builder()
        .withMajor(majorGroup)
        .withMinor(minorGroup)
        .withPatch(patchGroup)
        .withBuild(buildGroup)
        .build();
    }
  }

  record SemverVersionParser() implements VersionParser {

    @Override
    public Semver parse(String versionString) {
      Semver parsed = Semver.coerce(versionString);
      if (parsed == null) {
        throw new IllegalArgumentException("Could not parse version string as Semver: " + versionString);
      }
      return parsed;
    }
  }

  private static int intOrError(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Missing required version component");
    }

    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid number format for version component: " + value, e);
    }
  }

}
