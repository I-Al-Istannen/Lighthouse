package de.ialistannen.lighthouse.dockerconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class DockerConfig {

  /**
   * Loads the docker authentications from a given config file.
   *
   * @param pathToConfig the path to the docker config
   * @return the stored authentications
   * @throws IOException if an error occurs
   */
  public static List<DockerRegistryAuth> loadAuthentications(Path pathToConfig) throws IOException {
    ObjectNode root = new ObjectMapper().readValue(Files.readString(pathToConfig), ObjectNode.class);

    return DockerRegistryAuth.fromJson((ObjectNode) root.get("auths"));
  }
}
