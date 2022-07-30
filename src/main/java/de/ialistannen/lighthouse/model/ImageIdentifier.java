package de.ialistannen.lighthouse.model;

import de.ialistannen.lighthouse.registry.DockerLibraryHelper;

public record ImageIdentifier(String image, String tag) {

  public String nameWithTag() {
    return image() + ":" + tag();
  }

  /**
   * @param libraryHelper the library helper
   * @return a {@link DockerLibraryHelper#getFriendlyImageName(String) friendly} image name
   */
  public ImageIdentifier friendly(DockerLibraryHelper libraryHelper) {
    return new ImageIdentifier(
      libraryHelper.getFriendlyImageName(image()),
      tag()
    );
  }

  /**
   * Converts a string of the form {@code image:tag} to an {@link ImageIdentifier}.
   *
   * @param asString the image string
   * @return the created image identifier
   */
  public static ImageIdentifier fromString(String asString) {
    int imageStart = asString.lastIndexOf('/');
    int tagStart = asString.lastIndexOf(':');

    String tag = "latest";
    String image = asString;

    if (tagStart > imageStart) {
      tag = asString.substring(tagStart + 1);
      image = asString.substring(0, tagStart);
    }

    return new ImageIdentifier(image, tag);
  }
}
