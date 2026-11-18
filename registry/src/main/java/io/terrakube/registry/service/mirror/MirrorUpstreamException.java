package io.terrakube.registry.service.mirror;

/** An upstream registry could not be used safely: refused address, bad checksum, too large, or an error. */
public class MirrorUpstreamException extends RuntimeException {

    public MirrorUpstreamException(String message) {
        super(message);
    }

    public MirrorUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
