package de.ialistannen.lighthouse.updates;

import com.github.dockerjava.api.model.Container;

public class ContainerWithBaseUtils {

  private static final String BASE_IMAGE_LABEL = "lighthouse.base";

  public static boolean isTaggedWithBase(Container container) {
    return container.getLabels().containsKey(BASE_IMAGE_LABEL);
  }

  public static String getBaseImage(Container container) {
    return container.getLabels().get(BASE_IMAGE_LABEL).split(":")[0];
  }

  public static String getBaseTag(Container container) {
    String[] parts = container.getLabels().get(BASE_IMAGE_LABEL).split(":");
    if (parts.length == 1) {
      return "latest";
    }
    return parts[1];
  }

  public static String getBaseRepoTag(Container container) {
    return getBaseImage(container) + ":" + getBaseTag(container);
  }
}
