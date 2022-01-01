package de.ialistannen.lighthouse.hub;

public class TokenFetchException extends RuntimeException {

  public TokenFetchException(String message) {
    super(message);
  }

  public TokenFetchException(String message, Throwable cause) {
    super(message, cause);
  }
}
