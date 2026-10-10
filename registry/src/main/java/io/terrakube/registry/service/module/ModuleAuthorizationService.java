package io.terrakube.registry.service.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.registry.service.search.CommonSearchService;
import io.terrakube.registry.service.token.RegistryTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ModuleAuthorizationService {

    private static final String ISSUER = "TerrakubeInternal";
    private static final String SUBJECT = "TerrakubeInternal (REGISTRY)";
    private static final String EMAIL = "no-reply@terrakube.io";
    private static final String NAME = "TerrakubeInternal Client";

    private final CommonSearchService commonSearchService;
    private final RegistryTokenService registryTokenService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String internalSecret;
    private final String instanceOwner;
    private final String authType;

    private final Cache<String, Set<String>> orgTeamsCache;

    @org.springframework.beans.factory.annotation.Autowired
    public ModuleAuthorizationService(
            CommonSearchService commonSearchService,
            RegistryTokenService registryTokenService,
            @Value("${io.terrakube.client.url:}") String apiUrl,
            @Value("${io.terrakube.token.internal:}") String internalSecret,
            @Value("${io.terrakube.owner:}") String instanceOwner,
            @Value("${io.terrakube.registry.authentication.type:DEX}") String authType) {
        this(commonSearchService, registryTokenService, new RestTemplate(), new ObjectMapper(), apiUrl, internalSecret, instanceOwner, authType);
    }

    public ModuleAuthorizationService(
            CommonSearchService commonSearchService,
            String apiUrl,
            String internalSecret,
            String instanceOwner,
            String authType) {
        this(commonSearchService, new RegistryTokenService(internalSecret), new RestTemplate(), new ObjectMapper(), apiUrl, internalSecret, instanceOwner, authType);
    }

    public ModuleAuthorizationService(
            CommonSearchService commonSearchService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            String apiUrl,
            String internalSecret,
            String instanceOwner,
            String authType) {
        this(commonSearchService, new RegistryTokenService(internalSecret), restTemplate, objectMapper, apiUrl, internalSecret, instanceOwner, authType);
    }

    public ModuleAuthorizationService(
            CommonSearchService commonSearchService,
            RegistryTokenService registryTokenService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            String apiUrl,
            String internalSecret,
            String instanceOwner,
            String authType) {
        this.commonSearchService = commonSearchService;
        this.registryTokenService = registryTokenService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.internalSecret = internalSecret;
        this.instanceOwner = instanceOwner;
        this.authType = authType;
        this.orgTeamsCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();
    }

    public boolean isAuthorized(Authentication authentication, String organizationName) {
        if ("LOCAL".equalsIgnoreCase(authType)) {
            return true;
        }

        if (authentication == null) {
            return false;
        }

        if (authentication instanceof JwtAuthenticationToken jwt) {
            String iss = (String) jwt.getTokenAttributes().get("iss");
            if (ISSUER.equals(iss)) {
                return true;
            }

            List<String> groups = extractGroups(jwt);

            // Check instance owner
            if (instanceOwner != null && !instanceOwner.isBlank()) {
                Object email = jwt.getTokenAttributes().get("email");
                if (instanceOwner.equals(email) || groups.contains(instanceOwner)) {
                    return true;
                }
            }

            // Check organization teams
            Set<String> orgTeams = getOrganizationTeams(organizationName);
            for (String group : groups) {
                if (orgTeams.contains(group)) {
                    return true;
                }
            }
            return false;
        }

        return false;
    }

    public Set<String> getOrganizationTeams(String organizationName) {
        if (commonSearchService == null || organizationName == null || organizationName.isBlank()) {
            return Collections.emptySet();
        }

        try {
            String orgId = commonSearchService.getOrganizationId(organizationName);
            if (orgId == null || orgId.isBlank()) {
                return Collections.emptySet();
            }

            return orgTeamsCache.get(orgId, this::fetchTeamsFromApi);
        } catch (Exception e) {
            log.warn("Error resolving organization ID for {}: {}", organizationName, e.getMessage());
            return Collections.emptySet();
        }
    }

    public void setCachedTeams(String orgId, Set<String> teams) {
        orgTeamsCache.put(orgId, teams);
    }

    protected Set<String> fetchTeamsFromApi(String orgId) {
        if (apiUrl == null || apiUrl.isBlank() || internalSecret == null || internalSecret.isBlank()) {
            log.warn("Cannot fetch teams from API: apiUrl or internalSecret is not configured");
            return Collections.emptySet();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(generateInternalToken());
            headers.setAccept(List.of(
                    MediaType.parseMediaType("application/vnd.api+json"),
                    MediaType.APPLICATION_JSON));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String requestUrl = apiUrl + "/api/v1/organization/" + orgId + "/team";
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode data = root.path("data");
                Set<String> teams = new HashSet<>();
                if (data.isArray()) {
                    for (JsonNode teamNode : data) {
                        String teamName = teamNode.path("attributes").path("name").asText(null);
                        if (teamName != null && !teamName.isBlank()) {
                            teams.add(teamName);
                        }
                    }
                }
                log.info("Fetched {} teams from API for orgId {}", teams.size(), orgId);
                return teams;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch teams from API for orgId {}: {}", orgId, e.getMessage());
        }

        return Collections.emptySet();
    }

    public String generateInternalToken() {
        return registryTokenService != null ? registryTokenService.generateInternalToken() : "";
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGroups(JwtAuthenticationToken jwt) {
        Object groupsObj = jwt.getTokenAttributes().get("groups");
        if (groupsObj instanceof List<?>) {
            return (List<String>) groupsObj;
        } else if (groupsObj instanceof String groupStr) {
            return List.of(groupStr);
        }
        return Collections.emptyList();
    }
}
