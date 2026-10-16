package io.terrakube.api.plugin.security.audit;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Supplies an audit actor that takes precedence over request authentication.
 * Only trusted internal code may bind an actor, after verifying its source. Keep the
 * scope limited to the database save; this context does not change authentication.
 */
public final class AuditActorContext {

    private static final int MAX_ACTOR_LENGTH = 128;
    private static final ScopedValue<Optional<String>> ACTOR = ScopedValue.newInstance();

    private AuditActorContext() {}

    public static Optional<String> currentActor() {
        return ACTOR.isBound() ? ACTOR.get() : Optional.empty();
    }

    /** Bind on the saving thread; a missing or normalized-empty actor keeps the existing auditor. */
    public static <T> T withActor(String actor, Supplier<T> action) {
        return ScopedValue.where(ACTOR, normalize(actor)).call(action::get);
    }

    private static Optional<String> normalize(String actor) {
        if (actor == null) {
            return Optional.empty();
        }
        StringBuilder clean = new StringBuilder();
        actor.codePoints().filter(codePoint -> switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        }).forEach(clean::appendCodePoint);
        String normalized = clean.toString().strip();
        if (normalized.length() > MAX_ACTOR_LENGTH) {
            // Cap UTF-16 length without splitting a surrogate pair.
            int end = Character.isHighSurrogate(normalized.charAt(MAX_ACTOR_LENGTH - 1))
                    ? MAX_ACTOR_LENGTH - 1 : MAX_ACTOR_LENGTH;
            normalized = normalized.substring(0, end).stripTrailing();
        }
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }
}
