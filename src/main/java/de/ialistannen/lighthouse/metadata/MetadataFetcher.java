package de.ialistannen.lighthouse.metadata;

import de.ialistannen.lighthouse.registry.RemoteImageMetadata;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;

public interface MetadataFetcher {

  Optional<RemoteImageMetadata> fetch(String image, String tag)
    throws IOException, InterruptedException, URISyntaxException;
}
