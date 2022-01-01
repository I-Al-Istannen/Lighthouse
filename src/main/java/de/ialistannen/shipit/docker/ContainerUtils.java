package de.ialistannen.shipit.docker;

import com.github.dockerjava.api.model.Container;

public class ContainerUtils {

  private static final String SHIP_IT_LABEL = "ship-it.base";

  public static boolean isParticipating(Container container) {
    return container.getLabels().containsKey(SHIP_IT_LABEL);
  }

  public static String getImage(Container container) {
    return container.getLabels().get(SHIP_IT_LABEL).split(":")[0];
  }

  public static String getTag(Container container) {
    return container.getLabels().get(SHIP_IT_LABEL).split(":")[1];
  }

  public static String getBaseRepoTag(Container container) {
    return getImage(container) + ":" + getTag(container);
  }
}
