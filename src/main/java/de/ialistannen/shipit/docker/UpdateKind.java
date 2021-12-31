package de.ialistannen.shipit.docker;

public enum UpdateKind {
  REFERENCE_IMAGE_IS_OUTDATED(0xfec900),
  CONTAINER_USES_OUTDATED_BASE_IMAGE(0xff6347);

  private final int color;

  UpdateKind(int color) {
    this.color = color;
  }

  public int getColor() {
    return color;
  }
}
