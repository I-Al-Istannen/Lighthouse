package de.ialistannen.shipit.hub;

public class DigestFetchException extends RuntimeException {

  public DigestFetchException(int statusCode) {
    super("Error fetching digest, got status code " + statusCode);
  }
}
