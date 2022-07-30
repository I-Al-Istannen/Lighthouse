package de.ialistannen.lighthouse.updates;

import com.github.dockerjava.api.model.Container;
import de.ialistannen.lighthouse.model.ImageIdentifier;

public class ContainerWithBaseUtils {

  private static final String BASE_IMAGE_LABEL = "lighthouse.base";

  public static boolean isTaggedWithBase(Container container) {
    return container.getLabels().containsKey(BASE_IMAGE_LABEL);
  }

  public static ImageIdentifier getBaseImageIdentifier(Container container) {
    return ImageIdentifier.fromString(container.getLabels().get(BASE_IMAGE_LABEL));
  }

}
