package io.wisetime.connector.patricia.util;

/**
 * General error designed to contain connector's specific meaningful message which can be shown to a user.
 *
 * @author yehor.lashkul
 */
public class ConnectorException extends RuntimeException {

  public ConnectorException(String message) {
    super(message);
  }
}
