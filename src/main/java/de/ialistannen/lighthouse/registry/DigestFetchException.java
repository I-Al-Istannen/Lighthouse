package de.ialistannen.lighthouse.registry;

public class DigestFetchException extends RuntimeException {

  public DigestFetchException(String imageTag, int statusCode) {
    super("Error fetching digest for '" + imageTag + "' , got status code " + statusCode);
  }

}
