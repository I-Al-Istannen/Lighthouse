package de.ialistannen.shipit.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.ialistannen.shipit.library.LibraryHelper;
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

public class DockerRegistryClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(DockerRegistryClient.class);

  private final HttpClient client;
  private final ObjectMapper objectMapper;
  private final LibraryHelper libraryHelper;
  private Cache<String, String> cache;
  private Duration currentAssumedTokenLifetime;

  public DockerRegistryClient(HttpClient client, LibraryHelper libraryHelper) {
    this.client = client;
    this.libraryHelper = libraryHelper;
    this.objectMapper = new ObjectMapper().findAndRegisterModules();
    this.currentAssumedTokenLifetime = Duration.ofSeconds(300);
    this.cache = buildCache();
  }

  private Cache<String, String> buildCache() {
    LOGGER.info("Building token cache with assumed lifetime {}", currentAssumedTokenLifetime);
    return Caffeine.newBuilder()
      .expireAfterAccess(currentAssumedTokenLifetime.toSeconds() - 10, TimeUnit.SECONDS)
      .build();
  }

  public ImageInformation fetchImageInformationForTag(String repoTag)
    throws IOException, URISyntaxException, InterruptedException {
    String[] parts = repoTag.split(":");
    String image = parts[0];
    String tag = parts[1];

    if (libraryHelper.isLibraryImage(image)) {
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

  public String fetchImageDigestForTag(String repoTag) throws IOException, URISyntaxException, InterruptedException {
    String[] parts = repoTag.split(":");
    String image = parts[0];
    String tag = parts[1];

    if (libraryHelper.isLibraryImage(image)) {
      image = "library/" + image;
    }

    String token = cache.get(image, this::fetchTokenSilent);

    return fetchImageDigestForTag(image, tag, token);
  }

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

  private String fetchTokenSilent(String image) {
    try {
      return fetchToken(image);
    } catch (IOException | InterruptedException | URISyntaxException e) {
      throw new TokenFetchException("Error fetching auth token", e);
    }
  }

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
