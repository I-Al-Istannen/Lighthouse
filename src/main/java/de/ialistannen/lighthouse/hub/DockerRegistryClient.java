package de.ialistannen.lighthouse.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A client for <a href="hub.docker.com">hub.docker.com</a> and <a href="index.docker.io">index.docker.io</a> that can
 * fetch the latest manifest digest and some basic information about an image.
 */
public class DockerRegistryClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(DockerRegistryClient.class);

  private final HttpClient client;
  private final ObjectMapper objectMapper;
  private final DockerLibraryHelper dockerLibraryHelper;

  private Cache<String, String> cache;
  private Duration currentAssumedTokenLifetime;

  public DockerRegistryClient(HttpClient client, DockerLibraryHelper dockerLibraryHelper) {
    this.client = client;
    this.dockerLibraryHelper = dockerLibraryHelper;

    this.objectMapper = new ObjectMapper().findAndRegisterModules();
    this.currentAssumedTokenLifetime = Duration.ofSeconds(300);
    this.cache = buildCache();
  }

  private Cache<String, String> buildCache() {
    // We cache the tokens but release them before they become invalid
    LOGGER.info("Building token cache with assumed lifetime {}", currentAssumedTokenLifetime);

    return Caffeine.newBuilder()
      .expireAfterAccess(currentAssumedTokenLifetime.toSeconds() - 10, TimeUnit.SECONDS)
      .build();
  }

  /**
   * Fetches the latest information about a repo from docker hub.
   *
   * @param repoTag the combined image name and tag in the following format: {@code <image name>:<tag>}
   * @return information about the image sourced from docker hub
   * @throws IOException if an error occurs
   * @throws URISyntaxException if you introduce invalid characters
   * @throws InterruptedException ?
   */
  public ImageInformation fetchImageInformationForTag(String repoTag)
    throws IOException, URISyntaxException, InterruptedException {
    String[] parts = repoTag.split(":");
    String image = parts[0];
    String tag = parts[1];

    if (dockerLibraryHelper.isLibraryImage(image)) {
      image = "library/" + image;
    }

    String url = "https://hub.docker.com/v2/repositories/%s/tags/%s/" .formatted(image, tag);
    HttpRequest request = HttpRequest.newBuilder(new URI(url)).GET().build();
    String body = client.send(request, BodyHandlers.ofString()).body();
    ObjectNode root = objectMapper.readValue(body, ObjectNode.class);

    return new ImageInformation(
      root.get("last_updater_username").asText(),
      Instant.parse(root.get("last_updated").asText()),
      image,
      tag
    );
  }

  /**
   * Fetches the image digest for a given image and tag, using the passed token for authentication. The digest is taken
   * from the received HEADER, as that does not seem to count against the API request limit.
   * <p>
   * The manifest digest is NOT the image ID, but can be found in the local image manifest as "{@code RepoDigests}".
   *
   * @param repoTag the combined image name and tag in the following format: {@code <image name>:<tag>}
   * @return the digest of the manifest
   * @throws IOException if an error happens
   * @throws InterruptedException ?
   * @throws URISyntaxException if you introduce invalid characters
   * @throws DigestFetchException if the server denied the request
   * @throws TokenFetchException if the auth token could not be fetched
   */
  public String fetchImageDigestForTag(String repoTag) throws IOException, URISyntaxException, InterruptedException {
    String[] parts = repoTag.split(":");
    String image = parts[0];
    String tag = parts[1];

    if (dockerLibraryHelper.isLibraryImage(image)) {
      image = "library/" + image;
    }

    String token = cache.get(image, this::fetchTokenSilent);

    return fetchImageDigestForTag(image, tag, token);
  }

  /**
   * Fetches the image digest for a given image and tag, using the passed token for authentication. The digest is taken
   * from the received HEADER, as that does not seem to count against the API request limit.
   * <p>
   * The manifest digest is NOT the image ID, but can be found in the local image manifest as "{@code RepoDigests}".
   *
   * @param image the image to get the digest for
   * @param tag the tag to get the digest for
   * @param token the token to use
   * @return the digest of the manifest
   * @throws IOException if an error happens
   * @throws InterruptedException ?
   * @throws URISyntaxException if you introduce invalid characters
   * @throws DigestFetchException if the server denied the request
   */
  private String fetchImageDigestForTag(String image, String tag, String token)
    throws IOException, InterruptedException, URISyntaxException {
    LOGGER.debug("Fetching digest for '{}':'{}'", image, tag);

    String url = "https://index.docker.io/v2/%s/manifests/%s" .formatted(image, tag);

    HttpRequest request = HttpRequest.newBuilder(new URI(url))
      .header("Accept", "application/vnd.docker.distribution.manifest.list.v2+json")
      .header("Accept", "application/vnd.docker.distribution.manifest.v1+json")
      .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
      .header("Authorization", "Bearer %s" .formatted(token))
      .method("HEAD", BodyPublishers.noBody())
      .build();

    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      LOGGER.info(
        "Failed to fetch image digest tag for '{}':'{}' ({}): {}",
        image,
        tag,
        response.statusCode(),
        response.body()
      );
      throw new DigestFetchException(response.statusCode());
    }
    return response.headers().firstValue("docker-content-digest").orElseThrow();
  }

  /**
   * Fetches the token an converts any error into a {@link TokenFetchException}.
   *
   * @param image the the image to request a token for
   * @return the received token
   * @throws TokenFetchException if anything goes wrong
   * @see #fetchToken(String)
   */
  private String fetchTokenSilent(String image) {
    try {
      return fetchToken(image);
    } catch (IOException | InterruptedException | URISyntaxException e) {
      throw new TokenFetchException("Error fetching auth token", e);
    }
  }

  /**
   * Due to rate limiting you need to request a token from the docker registry if you want to view its manifests. This
   * can be done without logging in, but must be done once per image.
   *
   * @param image the image to request a token for
   * @return the received token
   * @throws IOException if an error happens
   * @throws InterruptedException help?
   * @throws URISyntaxException if the image turns the URL into an invalid URI...
   */
  private String fetchToken(String image)
    throws IOException, InterruptedException, URISyntaxException {
    LOGGER.debug("Fetching token for {}", image);

    String url = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull" .formatted(image);

    HttpRequest request = HttpRequest.newBuilder(new URI(url)).build();

    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    String body = response.body();
    ObjectNode rootNode = objectMapper.readValue(body, ObjectNode.class);

    int expiresInSeconds = rootNode.get("expires_in").asInt();
    if (expiresInSeconds != currentAssumedTokenLifetime.toSeconds()) {
      currentAssumedTokenLifetime = Duration.ofSeconds(expiresInSeconds);
      cache = buildCache();
    }

    return rootNode.get("token").asText();
  }
}
