package io.terrakube.api.plugin.security.federated;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Normalizes the standard claims used to select a federated identity provider.
 *
 * <p>OIDC permits {@code aud} to be either a string or an array. Spring Security also normalizes a
 * scalar audience to a collection after decoding, so authorization code must support both forms.
 */
public final class FederatedTokenClaims {

    private FederatedTokenClaims() {}

    public static String issuer(Map<String, Object> tokenAttributes) {
        Object issuer = tokenAttributes.get("iss");
        return issuer instanceof String ? (String) issuer : "";
    }

    public static List<String> audiences(Map<String, Object> tokenAttributes) {
        Object audience = tokenAttributes.get("aud");
        if (audience instanceof String value) {
            return value.isEmpty() ? List.of() : List.of(value);
        }
        if (audience instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .toList();
        }
        return List.of();
    }
}
