package de.ialistannen.lighthouse.docker;

import com.github.dockerjava.api.model.Container;

public class ContainerUtils {

  private static final String BASE_IMAGE_LABEL = "lighthouse.base";

  public static boolean isParticipating(Container container) {
    return container.getLabels().containsKey(BASE_IMAGE_LABEL);
  }

  public static String getImage(Container container) {
    return container.getLabels().get(BASE_IMAGE_LABEL).split(":")[0];
  }

  public static String getTag(Container container) {
    return container.getLabels().get(BASE_IMAGE_LABEL).split(":")[1];
  }

  public static String getBaseRepoTag(Container container) {
    return getImage(container) + ":" + getTag(container);
  }
}
