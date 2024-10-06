package de.ialistannen.lighthouse.notifier;

import com.google.common.base.Throwables;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notification with ntfy.
 *
 * @see <a href="https://docs.ntfy.sh/publish/">ntfy docs</a>
 */
public class NtfyNotifier implements Notifier {

  private static final String LIGHTHOUSE_LOGO =
      "https://raw.githubusercontent.com/I-Al-Istannen/Lighthouse/master/media/lighthouse.png?raw=true";

  private static final Logger LOGGER = LoggerFactory.getLogger(NtfyNotifier.class);

  private final HttpClient httpClient;
  private final URI url;
  private final Optional<String> hostname;

  public NtfyNotifier(HttpClient httpClient, URI url, Optional<String> hostname) {
    this.httpClient = httpClient;
    this.url = url;
    this.hostname = hostname;
  }

  @Override
  public void notify(Throwable e) {
    LOGGER.info("Notifying for exception in ntfy");

    String stacktrace = Throwables.getStackTraceAsString(e);
    // Messages longer than 4096 bytes are treated as attachment
    if (stacktrace.length() > 4000) {
      stacktrace = stacktrace.substring(0, 4000);
    }

    HttpRequest request = HttpRequest.newBuilder(url)
      .header("X-Title", "Lighthouse Error" + hostname.map(h -> " (" + h + ")").orElse(""))
      .header("X-Tags", "warning")
      .header("X-Icon", LIGHTHOUSE_LOGO)
      .POST(BodyPublishers.ofString(stacktrace))
      .build();
    send(request);
  }

  @Override
  public void notify(List<LighthouseContainerUpdate> updates) {
    if (updates.isEmpty()) {
      return;
    }
    LOGGER.info("Notifying in ntfy");

    for (LighthouseContainerUpdate update : updates) {
      HttpRequest request = HttpRequest.newBuilder(url)
        .header("X-Title", "Lighthouse" + hostname.map(h -> " (" + h + ")").orElse(""))
        .header("X-Tags", "mailbox_with_mail")
        .header("X-Icon", LIGHTHOUSE_LOGO)
        .header("X-Click", "https://hub.docker.com/r/%s".formatted(update.imageUpdate().imageIdentifier().image()))
        .POST(BodyPublishers.ofString(buildPayload(update)))
        .build();
      send(request);
    }

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      // Update notifications should be sent after image notifications, how long delayed is not important
      LOGGER.debug("Interrupted while waiting for update notifications to be sent", e);
    }
    send(buildUpdateRequest(updates.size()));
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

  private String buildPayload(LighthouseContainerUpdate update) {
    LighthouseImageUpdate image = update.imageUpdate();

    return """
      Remote: %s
      Container: %s
      Images: %s
      Metadata: %s
      Digest: %s
      """.formatted(
        image.imageIdentifier().nameWithTag(),
        String.join(", ", update.names()),
        String.join(", ", image.sourceImageNames()),
        image.remoteImageMetadata().map(m -> "%s by %s".formatted(m.updateTime(), m.updatedBy())).orElse("unknown"),
        image.remoteManifestDigest()
      );
  }

  private HttpRequest buildUpdateRequest(int updates) {
    return HttpRequest.newBuilder(url)
      .header("X-Title", "Lighthouse Update" + hostname.map(h -> " (" + h + ")").orElse(""))
      .header("X-Tags", "envelope")
      .header("X-Icon", LIGHTHOUSE_LOGO)
      .header("X-Actions", "http, "
          + "Update, "
          + url.toString() + ", "
          + "headers.X-Title=Lighthouse Update" + hostname.map(h -> " (" + h + ")").orElse("") + ", "
          + "headers.X-Tags=page_facing_up, "
          + "headers.X-Icon=" + LIGHTHOUSE_LOGO + ", "
          + "body=Update all containers"
      )
      .POST(BodyPublishers.ofString("Click to apply %d update(s)".formatted(updates)))
      .build();
  }
}
