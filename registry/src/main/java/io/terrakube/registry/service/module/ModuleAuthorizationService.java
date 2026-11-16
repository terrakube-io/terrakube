package io.terrakube.registry.service.module;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.security.CallerClassification;
import io.terrakube.client.security.CallerType;
import io.terrakube.client.security.TerrakubeCallerClassifier;
import io.terrakube.registry.service.search.CommonSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ModuleAuthorizationService {

    private final CommonSearchService commonSearchService;
    private final TerrakubeClient terrakubeClient;
    private final String issuerUri;
    private final String instanceOwner;
    private final String authType;

    @Autowired
    public ModuleAuthorizationService(
            CommonSearchService commonSearchService,
            TerrakubeClient terrakubeClient,
            @Value("${io.terrakube.token.issuer-uri:}") String issuerUri,
            @Value("${io.terrakube.owner:}") String instanceOwner,
            @Value("${io.terrakube.registry.authentication.type:DEX}") String authType) {
        this.commonSearchService = commonSearchService;
        this.terrakubeClient = terrakubeClient;
        this.issuerUri = issuerUri;
        this.instanceOwner = instanceOwner;
        this.authType = authType;
    }

    public boolean isAuthorized(Authentication authentication, String organizationName) {
        if ("LOCAL".equalsIgnoreCase(authType)) {
            return true;
        }

        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || organizationName == null
                || organizationName.isBlank()) {
            return false;
        }

        CallerClassification caller = TerrakubeCallerClassifier.classify(
                jwt.getTokenAttributes(), issuerUri, terrakubeClient);

        if (caller.getCallerType() == CallerType.REGISTRY_SERVICE) {
            return true;
        }
        if (caller.getCallerType() == CallerType.EXECUTOR_SERVICE
                || caller.getCallerType() == CallerType.UNKNOWN_INTERNAL) {
            log.debug("Rejected internal caller type {} for organization {}", caller.getCallerType(), organizationName);
            return false;
        }
        if (caller.getCallerType() == CallerType.FEDERATED
                && (!caller.isFederatedClaimsVerified() || caller.getEffectiveGroups().isEmpty())) {
            log.debug("Rejected unverified federated caller for organization {}", organizationName);
            return false;
        }

        String targetOrgId = resolveOrganizationId(organizationName);
        if (targetOrgId == null) {
            return false;
        }

        return TerrakubeCallerClassifier.isAuthorizedForOrganization(
                caller, targetOrgId, instanceOwner, terrakubeClient);
    }

    private String resolveOrganizationId(String organizationName) {
        if (commonSearchService == null || organizationName == null || organizationName.isBlank()) {
            return null;
        }
        try {
            String orgId = commonSearchService.getOrganizationId(organizationName);
            return (orgId != null && !orgId.isBlank()) ? orgId : null;
        } catch (Exception e) {
            log.warn("Error resolving organization ID for {}: {}", organizationName, e.getMessage());
            return null;
        }
    }
}
