package io.terrakube.executor.service.workspace.security;

import java.util.concurrent.atomic.AtomicReference;

public final class RunnerTokenHolder {

    private static final AtomicReference<String> TOKEN = new AtomicReference<>();

    private RunnerTokenHolder() {
    }

    public static void set(String token) {
        TOKEN.set(token);
    }

    public static boolean isSet() {
        return TOKEN.get() != null;
    }

    public static String require() {
        String token = TOKEN.get();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Hardened runner has no job token");
        }
        return token;
    }

    public static void clear() {
        TOKEN.set(null);
    }
}
