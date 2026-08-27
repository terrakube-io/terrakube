package io.terrakube.api.plugin.security.request;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.function.Supplier;

/**
 * Memoizes a lookup for the life of the current request.
 *
 * <p>Security checks run once per record, and Elide materializes and permission-checks every member
 * of a to-many relationship just to emit its {@code {type, id}} linkage. A lookup reached from a
 * check therefore repeats with identical arguments as many times as the response has records, which
 * makes its cost scale with total account activity rather than with the requested resource.
 *
 * <p>Request scope is deliberate. The rows these lookups read are mutable at runtime through the
 * API, so a cross-request cache would keep revoked access working until expiry.
 *
 * <p>Entries are stored one per attribute slot rather than in a shared container:
 * {@link RequestAttributes} exposes no atomic get-or-create, so publishing a lazily created
 * container needs a get-then-set that can drop an entry under concurrency. Independent slots cannot,
 * because a racing writer only rewrites its own key with an equal value.
 */
public final class RequestScopedMemo {

    private RequestScopedMemo() {
    }

    /**
     * Returns the memoized value for {@code key}, loading it on first use. Falls through to the
     * loader when no request is bound, which covers the security filter chain, scheduled jobs and
     * subscriptions.
     *
     * <p>The key must contain every input the value depends on. Anything the loader reads but the
     * key omits would let one caller's result stand in for another's, which for a lookup behind an
     * authorization check is a privilege bug rather than a stale read.
     */
    public static <T> T memoize(String namespace, List<?> key, Supplier<T> loader) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return loader.get();
        }

        String slot = namespace + '#' + key;
        Entry entry = (Entry) attributes.getAttribute(slot, RequestAttributes.SCOPE_REQUEST);
        if (entry != null && entry.answers(namespace, key)) {
            @SuppressWarnings("unchecked")
            T cached = (T) entry.value();
            return cached;
        }

        T value = loader.get();
        attributes.setAttribute(slot, new Entry(namespace, key, value), RequestAttributes.SCOPE_REQUEST);
        return value;
    }

    /**
     * Slot names flatten the key, so distinct keys can land on one name. The structured key is kept
     * beside the value and compared element-wise on read, so a collision re-loads rather than
     * handing back the other key's value.
     */
    private record Entry(String namespace, List<?> key, Object value) {

        boolean answers(String namespace, List<?> key) {
            return this.namespace.equals(namespace) && this.key.equals(key);
        }
    }
}
