package de.ialistannen.lighthouse.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * A helper for Docker's "official libraries" program. The APIs assume you magically know what images are official so
 * you can add the necessary "library/" prefix. This helper asks GitHub what the current state of the repo is to know
 * that without a crystal ball.
 */
public class DockerLibraryHelper {

  private final Set<String> libraryImages;

  public DockerLibraryHelper(HttpClient client) throws IOException, URISyntaxException, InterruptedException {
    this.libraryImages = fetchImages(client);
  }

  private Set<String> fetchImages(HttpClient client) throws IOException, URISyntaxException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(
        new URI("https://api.github.com/repos/docker-library/official-images/contents/library")
      )
      .header("Accept", "application/vnd.github.v3+json")
      .build();

    String jsonBody = client.send(request, BodyHandlers.ofString()).body();

    return StreamSupport.stream(new ObjectMapper().readValue(jsonBody, ArrayNode.class).spliterator(), false)
      .map(it -> it.get("path").asText())
      .map(it -> it.replaceFirst("library/", ""))
      .collect(Collectors.toSet());
  }

  /**
   * Returns the normalized image name, with the registry and "library/" prefixes prepended as needed.
   *
   * @param image the image name
   * @return the normalized image name
   */
  public String normalizeImageName(String image) {
    String result = image;
    if (isLibraryImage(image)) {
      result = "library/" + result;
    }

    if (!image.matches("(.+\\..+/).+")) {
      result = "docker.io/" + result;
    }

    if (result.startsWith("docker.io")) {
      result = result.replaceFirst("docker\\.io", "index.docker.io");
    }

    return result;
  }

  /**
   * Returns the normalized image name without the registry.
   *
   * @param image the image
   * @return the name without the registry
   */
  public String getImageNameWithoutRegistry(String image) {
    String name = normalizeImageName(image);
    return name.substring(name.indexOf('/') + 1);
  }

  /**
   * Returns the scope for auth requests for a given image.
   *
   * @param image the image
   * @return the scope for it
   */
  public String getScopeForImage(String image) {
    String normalizedName = normalizeImageName(image);
    normalizedName = normalizedName.substring(normalizedName.indexOf('/') + 1);

    return normalizedName;
  }

  /**
   * @param image the image name
   * @return the friendly name, i.e. without {@code "index.docker.io"} or {@code "library/"}
   */
  public String getFriendlyImageName(String image) {
    String name = normalizeImageName(image);
    if (name.startsWith("index.docker.io/")) {
      name = name.replaceFirst("index.docker.io/", "");
    }
    if (name.startsWith("library/")) {
      name = name.replaceFirst("library/", "");
    }
    return name;
  }

  private boolean isLibraryImage(String image) {
    if (image.startsWith("docker.io/")) {
      return isLibraryImage(image.replaceFirst("docker.io/", ""));
    }
    if (image.startsWith("index.docker.io/")) {
      return isLibraryImage(image.replaceFirst("index.docker.io/", ""));
    }
    return libraryImages.contains(image);
  }
}
