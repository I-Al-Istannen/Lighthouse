package de.ialistannen.lighthouse.updater;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback.Adapter;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DockerUpdater {

  private static final Logger LOGGER = LoggerFactory.getLogger(DockerUpdater.class);

  private final DockerClient client;
  private final List<Bind> updaterMounts;
  private final String updaterEntrypoint;
  private final String updaterDockerImage;

  public DockerUpdater(
    DockerClient client,
    List<String> updaterMounts,
    String updaterEntrypoint,
    String updaterDockerImage
  ) {
    this.client = client;
    this.updaterMounts = mountsToBinds(updaterMounts);
    this.updaterEntrypoint = updaterEntrypoint;
    this.updaterDockerImage = updaterDockerImage;

    // Clean up potentially leftover containers
    client.listContainersCmd()
      .withStatusFilter(Set.of("exited", "created"))
      .withShowAll(true)
      .withLabelFilter(Set.of("lighthouse-builder-container"))
      .exec()
      .forEach(container -> client.removeContainerCmd(container.getId()).exec());
  }

  public void updateBaseImage(LighthouseImageUpdate update) throws InterruptedException {
    LOGGER.info("Updating base image {} for {}", update.imageIdentifier().nameWithTag(), update.sourceImageNames());
    client.pullImageCmd(update.imageIdentifier().image())
      .withTag(update.imageIdentifier().tag())
      .exec(new PullImageResultCallback())
      .awaitCompletion(5, TimeUnit.MINUTES);
  }

  public void rebuildContainers(List<LighthouseContainerUpdate> updates, Consumer<String> successHook)
    throws InterruptedException {
    LOGGER.info("Rebuilding {} containers", updates.size());
    Set<LighthouseImageUpdate> imageUpdates = updates.stream()
      .map(LighthouseContainerUpdate::imageUpdate)
      .collect(Collectors.toSet());

    for (LighthouseImageUpdate update : imageUpdates) {
      updateBaseImage(update);
    }

    List<String> command = updates.stream()
      .filter(it -> !it.isMyself())
      .map(LighthouseContainerUpdate::names)
      .flatMap(Collection::stream)
      .distinct()
      .collect(Collectors.toCollection(ArrayList::new));
    command.addFirst(updaterEntrypoint);

    callRebuildScript(command);

    List<LighthouseContainerUpdate> lighthouseUpdates = updates.stream()
      .filter(LighthouseContainerUpdate::isMyself)
      .toList();

    if (lighthouseUpdates.isEmpty()) {
      successHook.accept("Updated!");
    } else {
      successHook.accept("Updated (except for " + lighthouseUpdates.size() + " Lighthouse instances)!");
    }

    if (!lighthouseUpdates.isEmpty()) {
      LOGGER.info("Updating lighthouse itself, no real progress can be given.");
      if (lighthouseUpdates.size() > 1) {
        LOGGER.warn(
          "Multiple lighthouse instances detected. "
          + "Your use-case might be interesting to hear :) "
          + "~~Objects~~ *cliffs* in your mirror are closer than they appear..."
        );
      }
      List<String> updateCommand = lighthouseUpdates.stream()
        .flatMap(it -> it.names().stream())
        .collect(Collectors.toCollection(ArrayList::new));
      updateCommand.addFirst(updaterEntrypoint);
      callRebuildScript(updateCommand);
    }
  }

  private void callRebuildScript(List<String> command) throws InterruptedException {
    pullUpdaterImageIfNecessary();

    CreateContainerResponse containerResponse = client.createContainerCmd(updaterDockerImage)
      .withLabels(Map.of("lighthouse-builder-container", "true"))
      .withHostConfig(HostConfig.newHostConfig().withBinds(updaterMounts).withAutoRemove(true))
      .withCmd(command)
      .exec();
    LOGGER.info("Started updater has ID {}", containerResponse.getId());

    Adapter<Frame> attached = client.attachContainerCmd(containerResponse.getId())
      .withFollowStream(true)
      .withLogs(true)
      .withStdOut(true)
      .withStdErr(true)
      .exec(new Adapter<>() {
        @Override
        public void onNext(Frame object) {
          if (object.getStreamType() == StreamType.STDERR) {
            LOGGER.warn("[updater] {}", new String(object.getPayload(), StandardCharsets.UTF_8));
          } else {
            LOGGER.info("[updater] {}", new String(object.getPayload(), StandardCharsets.UTF_8));
          }
        }
      });

    client.startContainerCmd(containerResponse.getId()).exec();
    WaitContainerResultCallback waitCallback = client.waitContainerCmd(containerResponse.getId()).start();

    try (attached) {
      int statusCode = waitCallback.awaitStatusCode();

      if (statusCode != 0) {
        LOGGER.warn("Rebuild failed with exit code {}", statusCode);
        throw new RebuildFailedException("Rebuild script failed, exit code: " + statusCode);
      } else {
        LOGGER.info("Rebuild successful");
      }
    } catch (DockerClientException | IOException e) {
      LOGGER.info("Wait operation failed, updater status unknown", e);
      throw new RebuildFailedException("Waiting for rebuild script failed", e);
    }
  }

  private void pullUpdaterImageIfNecessary() throws InterruptedException {
    String updaterImageWithTag = updaterDockerImage.contains(":") ? updaterDockerImage : updaterDockerImage + ":latest";
    boolean needsToPullUpdater = client.listImagesCmd()
      .withReferenceFilter(updaterImageWithTag)
      .exec()
      .isEmpty();

    if (needsToPullUpdater) {
      LOGGER.info("Updater image not present locally, pulling");
      client.pullImageCmd(updaterImageWithTag.split(":")[0])
        .withTag(updaterImageWithTag.split(":")[1])
        .exec(new PullImageResultCallback())
        .awaitCompletion(5, TimeUnit.MINUTES);
    }
  }

  private static List<Bind> mountsToBinds(List<String> updaterMounts) {
    return updaterMounts.stream().map(DockerUpdater::mountToBind).toList();
  }

  private static Bind mountToBind(String mount) {
    String[] parts = mount.split(":");
    if (parts.length != 2) {
      throw new IllegalArgumentException("Mount '" + mount + "' did not conform to 'source:dest' format.");
    }
    return new Bind(parts[0], new Volume(parts[1]));
  }
}
