package io.terrakube.api.plugin.security.user;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Tokens signed with the internal secret come in two kinds. Service tokens act for the platform
 * itself. Job tokens carry a {@code workspaceId} claim and are written where the job's own code can
 * read them, so they are never trusted as the platform: they only read state shared with their
 * workspace.
 */
public final class InternalTokens {

    public static final String ISSUER = "TerrakubeInternal";
    public static final String WORKSPACE_CLAIM = "workspaceId";

    private InternalTokens() {
    }

    public static boolean isService(Map<String, Object> claims) {
        return ISSUER.equals(claims.get("iss")) && !claims.containsKey(WORKSPACE_CLAIM);
    }

    public static boolean isService(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt && isService(jwt.getTokenAttributes());
    }

    /** The workspace a job token is scoped to, or null for any other token. */
    public static String jobWorkspaceId(Map<String, Object> claims) {
        return ISSUER.equals(claims.get("iss")) ? (String) claims.get(WORKSPACE_CLAIM) : null;
    }
}
