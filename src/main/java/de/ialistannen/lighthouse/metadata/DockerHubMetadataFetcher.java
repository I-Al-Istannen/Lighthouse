package de.ialistannen.lighthouse.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;
import de.ialistannen.lighthouse.registry.RemoteImageMetadata;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.Optional;

public class DockerHubMetadataFetcher implements MetadataFetcher {

  private final DockerLibraryHelper libraryHelper;
  private final HttpClient client;
  private final ObjectMapper objectMapper;

  public DockerHubMetadataFetcher(DockerLibraryHelper libraryHelper, HttpClient client) {
    this.libraryHelper = libraryHelper;
    this.client = client;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public Optional<RemoteImageMetadata> fetch(String image, String tag)
    throws IOException, InterruptedException, URISyntaxException {
    String imageName = libraryHelper.normalizeImageName(image);

    if (!imageName.startsWith("docker.io") && !imageName.startsWith("index.docker.io")) {
      return Optional.empty();
    }
    imageName = libraryHelper.getImageNameWithoutRegistry(imageName);

    String url = "https://hub.docker.com/v2/repositories/%s/tags/%s/".formatted(imageName, tag);
    HttpRequest request = HttpRequest.newBuilder(new URI(url)).GET().build();
    String body = client.send(request, BodyHandlers.ofString()).body();
    ObjectNode root = objectMapper.readValue(body, ObjectNode.class);

    return Optional.of(
      new RemoteImageMetadata(
        root.get("last_updater_username").asText(),
        Instant.parse(root.get("last_updated").asText())
      )
    );
  }

}
