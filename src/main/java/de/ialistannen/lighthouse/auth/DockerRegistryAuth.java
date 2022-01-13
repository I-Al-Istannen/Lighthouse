package de.ialistannen.lighthouse.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

public record DockerRegistryAuth(String url, String encodedAuth) {

  /**
   * Loads the docker authentications from a given config file.
   *
   * @param pathToConfig the path to the docker config
   * @return the stored authentications
   * @throws IOException if an error occurs
   */
  public static List<DockerRegistryAuth> loadAuthentications(Path pathToConfig) throws IOException {
    ObjectNode root = new ObjectMapper().readValue(Files.readString(pathToConfig), ObjectNode.class);

    return fromJson((ObjectNode) root.get("auths"));
  }

  /**
   * Extracts the stored registry authentications from the "auths" part of the config file.
   *
   * @param authsNode the auths node
   * @return the found docker registry authentications
   */
  private static List<DockerRegistryAuth> fromJson(ObjectNode authsNode) {
    List<DockerRegistryAuth> auths = new ArrayList<>();

    var iterator = authsNode.fields();
    while (iterator.hasNext()) {
      Entry<String, JsonNode> entry = iterator.next();
      auths.add(new DockerRegistryAuth(entry.getKey(), entry.getValue().get("auth").asText()));
    }

    return auths;
  }
}
