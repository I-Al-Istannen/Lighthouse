package de.ialistannen.lighthouse.updater;

public class RebuildFailedException extends RuntimeException {

  public RebuildFailedException(String message) {
    super(message);
  }

  public RebuildFailedException(String message, Throwable cause) {
    super(message, cause);
  }

}
