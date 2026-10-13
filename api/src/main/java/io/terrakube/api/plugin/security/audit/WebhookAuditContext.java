package io.terrakube.api.plugin.security.audit;

import java.util.Optional;
import java.util.function.Supplier;

/** Supplies a verified provider actor to auditing without changing request authentication. */
public final class WebhookAuditContext {

    private static final ScopedValue<Optional<String>> ACTOR = ScopedValue.newInstance();

    private WebhookAuditContext() {
    }

    public static Optional<String> currentActor() {
        return ACTOR.isBound() ? ACTOR.get() : Optional.empty();
    }

    /** Bind on the saving worker, after webhook verification, and only for the database write. */
    public static <T> T withActor(String actor, Supplier<T> action) {
        Optional<String> identity = Optional.ofNullable(actor).filter(value -> !value.isBlank());
        return ScopedValue.where(ACTOR, identity).call(action::get);
    }
}
