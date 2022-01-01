package de.ialistannen.lighthouse.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

public record DockerRegistryAuth(String host, String encodedAuth) {

  /**
   * Extracts the stored registry authentications from the "auths" part of the config file.
   *
   * @param authsNode the auths node
   * @return the found docker registry authentications
   */
  public static List<DockerRegistryAuth> fromJson(ObjectNode authsNode) {
    List<DockerRegistryAuth> auths = new ArrayList<>();

    var iterator = authsNode.fields();
    while (iterator.hasNext()) {
      Entry<String, JsonNode> entry = iterator.next();
      auths.add(new DockerRegistryAuth(entry.getKey(), entry.getValue().get("auth").asText()));
    }

    return auths;
  }
}
