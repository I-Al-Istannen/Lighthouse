package de.ialistannen.lighthouse.docker;

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
    return container.getLabels().get(BASE_IMAGE_LABEL).split(":")[1];
  }

  public static String getBaseRepoTag(Container container) {
    return getBaseImage(container) + ":" + getBaseTag(container);
  }
}
