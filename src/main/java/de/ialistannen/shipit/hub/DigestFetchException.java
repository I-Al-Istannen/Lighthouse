package de.ialistannen.shipit.hub;

public class DigestFetchException extends RuntimeException {

  public DigestFetchException(String message, Throwable cause) {
    super(message, cause);
  }

  public DigestFetchException(int statusCode) {
    super("Error fetching digest, got status code " + statusCode);
  }
}
