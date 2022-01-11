package de.ialistannen.lighthouse.model;

public enum BaseImageUpdateStrategy {
  /**
   * Pulls base images if they are not present locally.
   */
  ONLY_PULL_UNKNOWN,
  /**
   * Pulls base images if they are not present locally and updates them if they are outdated.
   */
  PULL_AND_UPDATE;

  public boolean updateOutdated() {
    return this == PULL_AND_UPDATE;
  }
}
