package io.terrakube.api.plugin.token.login;

public class BrokerUpstreamException extends RuntimeException {
    public BrokerUpstreamException(String message) {
        super(message);
    }

    public BrokerUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
