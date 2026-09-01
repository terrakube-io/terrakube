package io.terrakube.api.plugin.vcs;

import java.util.Locale;
import java.util.Map;

import org.springframework.util.LinkedCaseInsensitiveMap;

/**
 * HTTP header names are case-insensitive (RFC 9110 section 5.1), but the rest of the webhook
 * pipeline looks headers up with hard-coded lowercase keys ({@code headers.get("x-hub-signature-256")}
 * and friends). That only worked as long as something upstream happened to hand us lowercase keys -
 * older Spring MVC / servlet container versions did, but Spring Framework 7 (Spring Boot 4) now
 * resolves {@code @RequestHeader Map<String, String>} preserving the exact casing the client sent,
 * so GitHub's {@code X-Hub-Signature-256} stopped matching and every signed webhook failed
 * verification.
 *
 * <p>Rather than chase every {@code .get(...)} call site (and every future one), normalize the map
 * once at each point headers enter the pipeline - the public entry methods of {@link WebhookService}
 * and {@link RepoWebhookService}, and the JSON rehydration of a stored delivery in
 * {@link RepoWebhookDispatchService} - into a case-insensitive view. Callers keep using lowercase
 * keys and it just works regardless of what casing the client or the framework used.
 */
public final class WebhookHeaders {

    private WebhookHeaders() {
    }

    /**
     * Wraps {@code headers} in a case-insensitive map so lookups by any casing succeed. Returns an
     * empty (but still case-insensitive) map for {@code null} input. The returned map preserves
     * insertion order and the original key casing on iteration/serialization; only lookups are
     * case-insensitive.
     */
    public static Map<String, String> caseInsensitive(Map<String, String> headers) {
        LinkedCaseInsensitiveMap<String> result = new LinkedCaseInsensitiveMap<>(Locale.ROOT);
        if (headers != null) {
            result.putAll(headers);
        }
        return result;
    }
}
