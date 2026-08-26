package io.terrakube.api.plugin.variable;

public class IncompleteVariableException extends RuntimeException {
    public IncompleteVariableException(String message) {
        super(message);
    }
}
