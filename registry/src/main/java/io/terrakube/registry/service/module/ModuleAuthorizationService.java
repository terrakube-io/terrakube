package io.terrakube.registry.service.module;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.federated.Federated;
import io.terrakube.client.model.federated.claim.FederatedClaim;
import io.terrakube.client.model.organization.team.Team;
import io.terrakube.client.model.organization.workspace.Workspace;
import io.terrakube.client.model.organization.workspace.access.Access;
import io.terrakube.client.model.response.Response;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.client.security.CallerClassification;
import io.terrakube.client.security.CallerType;
import io.terrakube.client.security.TerrakubeCallerClassifier;
import io.terrakube.registry.service.search.CommonSearchService;
import io.terrakube.registry.service.search.RegistryPathValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ModuleAuthorizationService {

    private static final long CACHE_TTL_SECONDS = 60;

    private final CommonSearchService commonSearchService;
    private final TerrakubeClient terrakubeClient;
    private final TerrakubeClient cachedLookupClient;
    private final String issuerUri;
    private final String instanceOwner;
    private final String authType;

    private final Cache<String, String> orgIdCache;
    private final Cache<String, Response<List<Team>>> teamsCache;
    private final Cache<String, ResponseWithInclude<List<Workspace>, Access>> workspacesAccessCache;
    private final Cache<String, ResponseWithInclude<List<Federated>, FederatedClaim>> federatedCache;

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
        this.orgIdCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                .maximumSize(1000)
                .build();
        this.teamsCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                .maximumSize(1000)
                .build();
        this.workspacesAccessCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                .maximumSize(1000)
                .build();
        this.federatedCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                .maximumSize(1000)
                .build();
        this.cachedLookupClient = buildCachedLookupClient();
    }

    @PostConstruct
    void validateConfiguration() {
        if ("LOCAL".equalsIgnoreCase(authType)) {
            log.warn("Registry authorization is running with authentication type LOCAL; all requests are permitted.");
            return;
        }
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "io.terrakube.token.issuer-uri (DexIssuerUri) must be configured when io.terrakube.registry.authentication.type=" + authType);
        }
    }

    public boolean isAuthorized(Authentication authentication, String organizationName) {
        if ("LOCAL".equalsIgnoreCase(authType)) {
            return true;
        }

        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !RegistryPathValidator.isValidIdentifier(organizationName)) {
            return false;
        }

        Map<String, Object> tokenClaims = jwt.getTokenAttributes();
        CallerClassification caller = TerrakubeCallerClassifier.classify(
                tokenClaims, issuerUri, cachedLookupClient);

        if (caller.getCallerType() == CallerType.REGISTRY_SERVICE) {
            return true;
        }
        if (caller.getCallerType() == CallerType.EXECUTOR_SERVICE
                || caller.getCallerType() == CallerType.UNKNOWN_INTERNAL) {
            log.debug("Rejected internal caller type {} for organization {}", caller.getCallerType(), organizationName);
            return false;
        }

        String targetOrgId = resolveOrganizationId(organizationName);
        if (targetOrgId == null) {
            return false;
        }

        return TerrakubeCallerClassifier.isAuthorizedForOrganization(
                caller, targetOrgId, instanceOwner, cachedLookupClient);
    }

    private String resolveOrganizationId(String organizationName) {
        if (commonSearchService == null || !RegistryPathValidator.isValidIdentifier(organizationName)) {
            return null;
        }
        String cached = orgIdCache.getIfPresent(organizationName);
        if (cached != null) {
            return cached;
        }
        try {
            String orgId = commonSearchService.getOrganizationId(organizationName);
            if (orgId != null && !orgId.isBlank()) {
                orgIdCache.put(organizationName, orgId);
                return orgId;
            }
            return null;
        } catch (Exception e) {
            log.warn("Error resolving organization ID for {}: {}", organizationName, e.getMessage());
            return null;
        }
    }

    private TerrakubeClient buildCachedLookupClient() {
        if (terrakubeClient == null) {
            return null;
        }
        return (TerrakubeClient) Proxy.newProxyInstance(
                TerrakubeClient.class.getClassLoader(),
                new Class<?>[]{TerrakubeClient.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAllTeams" -> {
                            String orgId = (String) args[0];
                            Response<List<Team>> cached = teamsCache.getIfPresent(orgId);
                            if (cached != null) {
                                return cached;
                            }
                            Response<List<Team>> fresh = terrakubeClient.getAllTeams(orgId);
                            if (fresh != null && fresh.getData() != null) {
                                teamsCache.put(orgId, fresh);
                            }
                            return fresh;
                        }
                        case "getWorkspacesWithAccess" -> {
                            String orgId = (String) args[0];
                            ResponseWithInclude<List<Workspace>, Access> cached = workspacesAccessCache.getIfPresent(orgId);
                            if (cached != null) {
                                return cached;
                            }
                            ResponseWithInclude<List<Workspace>, Access> fresh = terrakubeClient.getWorkspacesWithAccess(orgId);
                            if (fresh != null && fresh.getData() != null) {
                                workspacesAccessCache.put(orgId, fresh);
                            }
                            return fresh;
                        }
                        case "getFederatedByIssuerUrlAndAudienceWithClaims" -> {
                            String issuer = (String) args[0];
                            String audience = (String) args[1];
                            String key = issuer + "\0" + audience;
                            ResponseWithInclude<List<Federated>, FederatedClaim> cached = federatedCache.getIfPresent(key);
                            if (cached != null) {
                                return cached;
                            }
                            ResponseWithInclude<List<Federated>, FederatedClaim> fresh =
                                    terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience);
                            if (fresh != null && fresh.getData() != null) {
                                federatedCache.put(key, fresh);
                            }
                            return fresh;
                        }
                        default -> {
                            try {
                                return method.invoke(terrakubeClient, args);
                            } catch (InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        }
                    }
                }
        );
    }
}
