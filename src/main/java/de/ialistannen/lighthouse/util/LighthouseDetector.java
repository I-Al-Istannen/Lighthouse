package de.ialistannen.lighthouse.util;

import com.github.dockerjava.api.model.Container;

public class LighthouseDetector {

  public static final String LIGHTHOUSE_ID_LABEL = "lighthouse.instance";

  public static boolean isLighthouse(Container container) {
    return container.getLabels().containsKey(LIGHTHOUSE_ID_LABEL);
  }

}
