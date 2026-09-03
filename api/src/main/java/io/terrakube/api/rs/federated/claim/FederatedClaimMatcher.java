package io.terrakube.api.rs.federated.claim;

import io.terrakube.api.rs.federated.Federated;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Slf4j
public final class FederatedClaimMatcher {

    private FederatedClaimMatcher() {}

    public static boolean matchesClaims(Federated federated, Map<String, Object> tokenAttributes) {
        List<FederatedClaim> claims = federated.getClaims();
        if (claims == null || claims.isEmpty()) {
            log.debug("Federated credential {} has no claim conditions and is denied", federated.getId());
            return false;
        }
        for (FederatedClaim claim : claims) {
            Object tokenValue = tokenAttributes.get(claim.getClaimKey());
            if (tokenValue == null || !claimMatches(tokenValue, claim.getClaimValue())) {
                log.debug("Federated claim mismatch: expected {}={}, got {}", claim.getClaimKey(), claim.getClaimValue(), tokenValue);
                return false;
            }
        }
        return true;
    }

    private static boolean claimMatches(Object tokenValue, String expected) {
        if (tokenValue instanceof String) {
            return expected.equals(tokenValue);
        } else if (tokenValue instanceof Collection<?> values) {
            for (Object item : values) {
                if (item != null && expected.equals(item.toString())) {
                    return true;
                }
            }
            return false;
        } else if (tokenValue != null) {
            return expected.equals(tokenValue.toString());
        }
        return false;
    }
}
