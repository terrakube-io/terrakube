package io.terrakube.api.plugin.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.notification.sender.DestinationUrlValidator;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Variable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ProxyService {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{var\\.(\\w+)\\}\\}");

    private final RestTemplate restTemplate = RestTemplateFactory.build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkspaceRepository workspaceRepository;
    private final VariableRepository variableRepository;
    private final GlobalVarRepository globalVarRepository;
    private final DestinationUrlValidator destinationUrlValidator;

    public record ResolvedVar(String value, boolean sensitive) {}

    public ProxyService(
            WorkspaceRepository workspaceRepository,
            VariableRepository variableRepository,
            GlobalVarRepository globalVarRepository,
            DestinationUrlValidator destinationUrlValidator) {
        this.workspaceRepository = workspaceRepository;
        this.variableRepository = variableRepository;
        this.globalVarRepository = globalVarRepository;
        this.destinationUrlValidator = destinationUrlValidator;
    }

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public ResponseEntity<String> proxyRequest(RequestEntity<String> requestEntity, String targetUrl, String proxyHeadersJson, UUID workspaceId) {
        HttpMethod method = requestEntity.getMethod();
        HttpHeaders headers = new HttpHeaders();

        // 1. Fetch workspace variables into request-scoped map
        Map<String, ResolvedVar> vars = fetchWorkspaceVars(workspaceId);

        // 2. Interpolate targetUrl allowing ONLY non-sensitive variables
        try {
            targetUrl = replaceVarsInUrl(targetUrl, vars);
        } catch (IllegalArgumentException e) {
            log.warn("Blocked proxy request due to sensitive variable in URL: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        // 3. Validate target URL against SSRF
        try {
            destinationUrlValidator.validate("proxy", targetUrl);
        } catch (Exception e) {
            log.warn("Blocked SSRF attempt in ProxyService to URL: {}", targetUrl);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Target URL destination is not allowed");
        }

        // 4. Add custom headers (sensitive variables permitted in HTTP headers over TLS)
        if (proxyHeadersJson != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> customHeaders = objectMapper.readValue(proxyHeadersJson, Map.class);
                customHeaders.forEach((key, value) -> headers.set(key, replaceVars(value, vars)));
            } catch (Exception e) {
                log.error("Error parsing proxyheaders JSON: ", e);
                return ResponseEntity.badRequest().body("Invalid proxyheaders format");
            }
        }

        // 5. Interpolate body (sensitive variables permitted in request payload)
        String body = requestEntity.getBody();
        if (body != null) {
            try {
                String decodedBody = URLDecoder.decode(body, StandardCharsets.UTF_8);
                JsonNode jsonNode = objectMapper.readTree(decodedBody);
                JsonNode proxyBodyNode = jsonNode.get("proxyBody");
                if (proxyBodyNode != null) {
                    body = replaceVars(proxyBodyNode.asText(), vars);
                }
            } catch (Exception e) {
                log.error("Error processing request body: ", e);
            }
        }

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(targetUrl, method, entity, String.class);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (Exception e) {
            log.error("Error forwarding request to {}: {}", targetUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error forwarding request");
        }
    }

    public Map<String, ResolvedVar> fetchWorkspaceVars(UUID workspaceId) {
        Map<String, ResolvedVar> vars = new HashMap<>();
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(() -> new IllegalArgumentException("Invalid workspace ID"));
        Organization organization = workspace.getOrganization();

        List<Globalvar> globalVariables = globalVarRepository.findByOrganization(organization);
        if (globalVariables != null) {
            globalVariables.forEach(globalvar -> vars.put(globalvar.getKey(), new ResolvedVar(globalvar.getValue(), globalvar.isSensitive())));
        }

        List<Variable> variables = variableRepository.findByWorkspace(workspace).orElse(new ArrayList<>());
        variables.forEach(variable -> vars.put(variable.getKey(), new ResolvedVar(variable.getValue(), variable.isSensitive())));
        return Collections.unmodifiableMap(vars);
    }

    public String replaceVarsInUrl(String input, Map<String, ResolvedVar> vars) {
        if (input == null) {
            return null;
        }

        Matcher matcher = VAR_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            ResolvedVar var = vars.get(key);
            if (var != null) {
                if (var.sensitive()) {
                    throw new IllegalArgumentException("Interpolation of sensitive variable '" + key + "' into URL is strictly forbidden");
                }
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(var.value() != null ? var.value() : ""));
            } else {
                matcher.appendReplacement(buffer, matcher.group(0));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public String replaceVars(String input, Map<String, ResolvedVar> vars) {
        if (input == null) {
            return null;
        }

        Matcher matcher = VAR_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            ResolvedVar var = vars.get(key);
            String replacement = (var != null && var.value() != null) ? var.value() : matcher.group(0);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}