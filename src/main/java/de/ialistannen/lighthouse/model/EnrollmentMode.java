package de.ialistannen.lighthouse.model;

import com.github.dockerjava.api.model.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether to automatically enroll containers or not.
 */
public enum EnrollmentMode {
  OPT_IN,
  OPT_OUT;

  private static final Logger LOGGER = LoggerFactory.getLogger(EnrollmentMode.class);

  /**
   * @param container the container to check
   * @return true if the container is enrolled
   */
  public boolean isParticipating(Container container) {
    String enabledStatus = container.getLabels().get("lighthouse.enabled");

    if (enabledStatus == null && this == OPT_IN) {
      return false;
    }
    if (enabledStatus == null && this == OPT_OUT) {
      return false;
    }

    if (!enabledStatus.equalsIgnoreCase("true") && !enabledStatus.equalsIgnoreCase("false")) {
      LOGGER.warn("Container '{}' has an invalid value for 'lighthouse.enabled'", (Object) container.getNames());
    }

    return enabledStatus.equals("true");
  }
}
