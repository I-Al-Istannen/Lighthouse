package de.ialistannen.shipit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import de.ialistannen.shipit.docker.ContainerUpdateChecker;
import de.ialistannen.shipit.docker.ImageUpdateChecker;
import de.ialistannen.shipit.docker.ShipItContainerUpdate;
import de.ialistannen.shipit.hub.DockerRegistryClient;
import de.ialistannen.shipit.notifier.DiscordNotifier;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.List;

public class Main {

  public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
    HttpClient httpClient = HttpClient.newBuilder().build();

    DefaultDockerClientConfig.Builder config = DefaultDockerClientConfig.createDefaultConfigBuilder();
    DockerClient dockerClient = DockerClientBuilder.getInstance(config.build()).build();

    DockerRegistryClient registryClient = new DockerRegistryClient(httpClient);

    ImageUpdateChecker imageUpdateChecker = new ImageUpdateChecker(dockerClient, registryClient);
    ContainerUpdateChecker containerUpdateChecker = new ContainerUpdateChecker(dockerClient);

    DiscordNotifier notifier = new DiscordNotifier(
      httpClient,
      new URI(
        "https://discord.com/api/webhooks/926553476048240702/ehlzIHLst7nPaceiLh0vHrKbW4SOSkJptNw3LFctsrSMKXTtEjqZr9Yro_7Y8rNo-qOn"
      )
    );

    List<ShipItContainerUpdate> updates = containerUpdateChecker.check(imageUpdateChecker.check());
    for (ShipItContainerUpdate update : updates) {
      System.out.println(update);
    }
    notifier.notify(updates);
  }
}
