package io.terrakube.api.plugin.vcs.discovery;

public class VcsDiscoveryNotSupportedException extends RuntimeException {
    public VcsDiscoveryNotSupportedException(String message) {
        super(message);
    }
}
