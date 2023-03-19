package de.ialistannen.lighthouse.updater;

import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.notifier.Notifier;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NtfyUpdateListener implements UpdateListener, Runnable {

  private static final String LIGHTHOUSE_LOGO =
      "https://raw.githubusercontent.com/I-Al-Istannen/Lighthouse/master/media/lighthouse.png?raw=true";

  private static final Logger LOGGER = LoggerFactory.getLogger(NtfyUpdateListener.class);

  private final HttpClient httpClient;
  private final DockerUpdater updater;
  private final Notifier notifier;
  private final URI url;
  private final Optional<String> hostname;
  private List<LighthouseContainerUpdate> lastUpdates;

  public NtfyUpdateListener(HttpClient httpClient, DockerUpdater updater, Notifier notifier, URI url,
      Optional<String> hostname) {
    this.httpClient = httpClient;
    this.updater = updater;
    this.notifier = notifier;
    this.url = url;
    this.hostname = hostname;
    this.lastUpdates = new ArrayList<>();
  }

  @Override
  public void onUpdatesFound(List<LighthouseContainerUpdate> updates) {
    this.lastUpdates = updates;
  }

  @Override
  public void run() {
    try {
      HttpRequest request = HttpRequest.newBuilder(new URI(url.toString() + "/json"))
        .header("X-Title", "Lighthouse Update" + hostname.map(h -> " (" + h + ")").orElse(""))
        .header("X-Tags", "page_facing_up")
        .header("X-Message", "Update all containers")
        .build();
      listen(request);
    } catch (URISyntaxException e) {
      LOGGER.warn("Ntfy request URI is invalid", e);
    }
  }

  public void start() {
    new Thread(this).start();
  }

  private void listen(HttpRequest request) {
    httpClient.sendAsync(request, BodyHandlers.fromLineSubscriber(new UpdateRequestSubscriber()))
      .thenAccept(response -> {
        if (response.statusCode() != 200 && response.statusCode() != 204) {
          LOGGER.warn("Failed to listen (HTTP {})", response.statusCode());
        }
        retry(request);
      })
      .exceptionally(throwable -> {
        LOGGER.warn("Failed to listen", throwable);
        notifier.notify(throwable);
        retry(request);
        return null;
      });
  }

  private void retry(HttpRequest request) {
    LOGGER.info("Retrying to listen in 30 seconds");
    try {
      Thread.sleep(30000);
    } catch (InterruptedException e) {
      LOGGER.debug("Interrupted while waiting to retry", e);
    }
    listen(request);
  }

  class UpdateRequestSubscriber implements Flow.Subscriber<String> {
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      LOGGER.info("Listening for updates");
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(String item) {
      if (!item.contains("\"event\":\"message\"")) {
        return;
      }
      LOGGER.debug("Received update request from ntfy: {}", item);
      update();
    }

    @Override
    public void onError(Throwable throwable) {
      LOGGER.warn("Error while listening for updates", throwable);
      notifier.notify(throwable);
    }

    @Override
    public void onComplete() {
      LOGGER.info("Listening for updates completed");
    }

    private void update() {
      LOGGER.info("Received update request");

      CompletableFuture.runAsync(() -> {
          try {
            updater.rebuildContainers(lastUpdates, label -> LOGGER.info("Update finished"));
            success();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        })
        .exceptionally(throwable -> {
          LOGGER.warn("Error while updating all", throwable);
          notifier.notify(throwable);
          return null;
        });
    }

    private void success() {
      HttpRequest request = HttpRequest.newBuilder(url)
        .header("X-Title", "Lighthouse Update" + hostname.map(h -> " (" + h + ")").orElse(""))
        .header("X-Tags", "rocket")
        .header("X-Icon", LIGHTHOUSE_LOGO)
        .POST(BodyPublishers.ofString("All updates applied"))
        .build();
      send(request);
    }

    private void send(HttpRequest request) {
      try {
        LOGGER.debug("Sending webhook {}", request.bodyPublisher().orElse(BodyPublishers.noBody()));

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 204) {
          LOGGER.warn("Failed to notify (HTTP {}): {}", response.statusCode(), response.body());
        }
      } catch (IOException | InterruptedException e) {
        LOGGER.warn("Failed to notify!", e);
      }
    }
  }
}
