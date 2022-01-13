package de.ialistannen.lighthouse.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.updates.FilterException;
import de.ialistannen.lighthouse.updates.UpdateFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileUpdateFilter implements UpdateFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileUpdateFilter.class);

  private final Path storagePath;
  private final ObjectMapper objectMapper;
  private UpdateDatabase currentDatabase;

  public FileUpdateFilter(Path storagePath) {
    this.storagePath = storagePath;
    this.objectMapper = new ObjectMapper();
  }

  private UpdateDatabase loadDatabase() throws IOException {
    if (Files.notExists(storagePath)) {
      Files.createDirectories(storagePath.getParent());
      return new UpdateDatabase(new HashMap<>());
    }
    return objectMapper.readValue(Files.readString(storagePath), UpdateDatabase.class);
  }

  private void saveDatabase(UpdateDatabase database) throws IOException {
    Files.writeString(storagePath, objectMapper.writeValueAsString(database));
  }

  @Override
  public List<LighthouseContainerUpdate> filter(List<LighthouseContainerUpdate> updates) throws FilterException {
    // Checking for an empty list here would be quite a bit faster - but also make errors harder to detect
    // (you actually need an update then!)
    try {
      currentDatabase = loadDatabase();

      LOGGER.info("Loaded {} already known update(s)", currentDatabase.knownUpdates().size());

      List<LighthouseContainerUpdate> filtered = updates.stream()
        .filter(it -> {
          // Use the remote manifest as key: We want to notify if there is yet another update for our local image!
          boolean known = currentDatabase.knownUpdates().containsKey(it.imageUpdate().remoteManifestDigest());
          if (known) {
            LOGGER.info(
              "Skipping notify for {} - {}",
              it.imageUpdate().sourceImageNames(),
              it.imageUpdate().sourceImageId()
            );
            return false;
          }
          return true;
        })
        .toList();

      Map<String, KnownUpdate> newKnownUpdates = new HashMap<>(currentDatabase.knownUpdates());
      for (LighthouseContainerUpdate update : filtered) {
        String remoteManifest = update.imageUpdate().remoteManifestDigest();
        String localImageId = update.imageUpdate().sourceImageId();
        newKnownUpdates.put(
          remoteManifest,
          new KnownUpdate(remoteManifest, localImageId, update.imageUpdate().sourceImageNames(), update.names())
        );
      }
      currentDatabase = new UpdateDatabase(newKnownUpdates);

      return filtered;
    } catch (IOException e) {
      throw new FilterException("Failed to load database from " + storagePath, e);
    }
  }

  @Override
  public void commit() throws FilterException {
    try {
      saveDatabase(currentDatabase);
      LOGGER.debug("Committed database to {}", storagePath);
    } catch (IOException e) {
      throw new FilterException("Failed to save database to " + storagePath, e);
    }
  }

  @JsonSerialize
  @JsonDeserialize
  record KnownUpdate(
    String remoteManifest,
    String localImageId,
    List<String> repoTags,
    List<String> originalContainers
  ) {

  }

  @JsonSerialize
  @JsonDeserialize
  record UpdateDatabase(Map<String, KnownUpdate> knownUpdates) {

  }
}
