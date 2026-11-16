package io.terrakube.registry.service.module;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.federated.Federated;
import io.terrakube.client.model.federated.claim.FederatedClaim;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.step.Step;
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

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ModuleAuthorizationService {

    private static final long CACHE_TTL_SECONDS = 60;
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "running",
            "queue",
            "pending",
            "waitingApproval",
            "approved"
    );

    private final CommonSearchService commonSearchService;
    private final TerrakubeClient terrakubeClient;
    private final TerrakubeClient cachedLookupClient;
    private final String issuerUri;
    private final String instanceOwner;
    private final String authType;

    private final Cache<String, Optional<String>> orgIdCache;
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

        if (caller.getCallerType() == CallerType.WORKSPACE_EXECUTOR) {
            return isAuthorizedWorkspaceExecutor(caller, tokenClaims, targetOrgId);
        }

        return TerrakubeCallerClassifier.isAuthorizedForOrganization(
                caller, targetOrgId, instanceOwner, cachedLookupClient);
    }

    private boolean isAuthorizedWorkspaceExecutor(
            CallerClassification caller,
            Map<String, Object> tokenClaims,
            String targetOrgId) {
        if (terrakubeClient == null
                || !targetOrgId.equalsIgnoreCase(caller.getOrganizationId())
                || !isValidUuid(caller.getWorkspaceId())
                || !isValidUuid(caller.getStepId())
                || !RegistryPathValidator.isValidIdentifier(caller.getJobId())
                || !hasStrictStepBinding(tokenClaims, caller)) {
            return false;
        }

        try {
            ResponseWithInclude<Job, Step> jobResponse = terrakubeClient.getJobById(targetOrgId, caller.getJobId());
            if (jobResponse == null || jobResponse.getData() == null || jobResponse.getData().getAttributes() == null) {
                return false;
            }
            Job job = jobResponse.getData();
            String jobStatus = job.getAttributes().getStatus();
            if (jobStatus == null || !ACTIVE_STATUSES.contains(jobStatus)) {
                return false;
            }

            if (job.getRelationships() == null
                    || job.getRelationships().getWorkspace() == null
                    || job.getRelationships().getWorkspace().getData() == null
                    || !caller.getWorkspaceId().equalsIgnoreCase(job.getRelationships().getWorkspace().getData().getId())) {
                return false;
            }

            String stepStatus = findStepStatus(jobResponse.getIncluded(), caller.getStepId());
            if (stepStatus == null) {
                Response<Step> stepResponse = terrakubeClient.getStepById(targetOrgId, caller.getJobId(), caller.getStepId());
                if (stepResponse != null && stepResponse.getData() != null && stepResponse.getData().getAttributes() != null) {
                    stepStatus = stepResponse.getData().getAttributes().getStatus();
                }
            }
            return stepStatus != null && ACTIVE_STATUSES.contains(stepStatus);
        } catch (Exception e) {
            log.warn("Failed to verify runner job {} / step {} in org {}: {}",
                    caller.getJobId(), caller.getStepId(), targetOrgId, e.getMessage());
            return false;
        }
    }

    private boolean hasStrictStepBinding(Map<String, Object> tokenClaims, CallerClassification caller) {
        Object stepClaim = tokenClaims != null ? tokenClaims.get("stepId") : null;
        if (stepClaim instanceof String && !((String) stepClaim).isBlank()) {
            return true;
        }
        // When stepId is omitted and resolved via jti, require exact "<jobId>-<stepUuid>" format.
        Object jtiClaim = tokenClaims != null ? tokenClaims.get("jti") : null;
        if (!(jtiClaim instanceof String jti)) {
            return false;
        }
        return jti.equals(caller.getJobId() + "-" + caller.getStepId());
    }

    private String findStepStatus(List<Step> includedSteps, String stepId) {
        if (includedSteps == null || includedSteps.isEmpty()) {
            return null;
        }
        for (Step step : includedSteps) {
            if (step != null && stepId.equalsIgnoreCase(step.getId()) && step.getAttributes() != null) {
                return step.getAttributes().getStatus();
            }
        }
        return null;
    }

    private String resolveOrganizationId(String organizationName) {
        if (commonSearchService == null || !RegistryPathValidator.isValidIdentifier(organizationName)) {
            return null;
        }
        Optional<String> cached = orgIdCache.getIfPresent(organizationName);
        if (cached != null) {
            return cached.orElse(null);
        }
        try {
            String orgId = commonSearchService.getOrganizationId(organizationName);
            if (orgId != null && !orgId.isBlank()) {
                orgIdCache.put(organizationName, Optional.of(orgId));
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
                            return method.invoke(terrakubeClient, args);
                        }
                    }
                }
        );
    }

    private static boolean isValidUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
