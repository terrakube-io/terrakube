package io.terrakube.registry.service.mirror;

/** Every pull-through fetch slot stayed taken; the client should retry later (503). */
public class MirrorBusyException extends RuntimeException {
    public MirrorBusyException(String message) {
        super(message);
    }
}
