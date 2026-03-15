package io.terrakube.api.plugin.security.audit.dex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@Slf4j
public class DexAuditorAwareImpl implements AuditorAware<String> {
    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            log.debug("getCurrentAuditor: {}", authentication);
            if (authentication instanceof AnonymousAuthenticationToken)
                return Optional.empty();
            else
                return Optional.ofNullable(isServiceAccount(authentication) ? "serviceAccount" : getEmail(authentication));
        } else {
            return Optional.of("Internal");
        }
    }

    public String getEmail(Authentication authentication) {
        org.springframework.security.oauth2.jwt.Jwt principal = (org.springframework.security.oauth2.jwt.Jwt) authentication.getPrincipal();
        if (principal.getClaims().get("email") != null)
            return (String) principal.getClaims().get("email");
        else
            return "Unknown";
    }

    public boolean isServiceAccount(Authentication authentication) {
        return false;
    }
}