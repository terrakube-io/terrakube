package io.terrakube.api.plugin.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.globalvar.Globalvar;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ProxyService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkspaceRepository workspaceRepository;
    private final VariableRepository variableRepository;
    private final GlobalVarRepository globalVarRepository;

    public static final Map<String, String> VARS = new HashMap<>();

    public ProxyService(WorkspaceRepository workspaceRepository, VariableRepository variableRepository, GlobalVarRepository globalVarRepository, WebClient webClient) {
        this.workspaceRepository = workspaceRepository;
        this.variableRepository = variableRepository;
        this.globalVarRepository = globalVarRepository;
        this.webClient = webClient;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ResponseEntity<String> proxyRequest(RequestEntity<String> requestEntity, String targetUrl, String proxyHeadersJson, UUID workspaceId) {
        HttpMethod method = requestEntity.getMethod();
        HttpHeaders headers = new HttpHeaders();

        // Fetch workspace and variables
        fetchWorkspaceVars(workspaceId);

        // Replace variables in targetUrl
        targetUrl = replaceVars(targetUrl);

        // Add custom headers
        if (proxyHeadersJson != null) {
            try {
                Map<String, String> customHeaders = objectMapper.readValue(proxyHeadersJson, Map.class);
                customHeaders.forEach((key, value) -> headers.set(key, replaceVars(value)));
            } catch (Exception e) {
                log.error("Error parsing proxyheaders JSON: ", e);
            }
        }

        String body = requestEntity.getBody();
        if (body != null) {
            try {
                // Decode the body
                String decodedBody = URLDecoder.decode(body, StandardCharsets.UTF_8);

                // Extract proxyBody if present
                JsonNode jsonNode = objectMapper.readTree(decodedBody);
                JsonNode proxyBodyNode = jsonNode.get("proxyBody");
                if (proxyBodyNode != null) {
                    String proxyBodyString = proxyBodyNode.asText();
                    // Replace variables in the proxy body
                    String replacedBody = replaceVars(proxyBodyString);

                    // Reassign the processed body
                    body = replacedBody;
                }
            } catch (Exception e) {
                log.error("Error processing request body: ", e);
            }
        }

        // Log headers and body for debugging
        log.info("Request Headers: {}", headers);
        log.info("Request Body: {}", body);
        log.info("Target URL: {}", targetUrl);

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            if (method == null) {
                throw new IllegalArgumentException("HTTP method must not be null");
            }
            ResponseEntity<String> response = webClient.method(method)
                    .uri(targetUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(entity)
                    .retrieve()
                    .toEntity(String.class)
                    .block();
            if (response == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Response is null");
            }
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (Exception e) {
            log.error("Error forwarding request to {}: ", targetUrl, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error forwarding request");
        }
    }

    @Transactional
    public void fetchWorkspaceVars(UUID workspaceId) {
        VARS.clear();
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(() -> new IllegalArgumentException("Invalid workspace ID"));
        Organization organization = workspace.getOrganization();

        List<Globalvar> globalVariables = globalVarRepository.findByOrganization(organization);
        globalVariables.forEach(globalvar -> VARS.put(globalvar.getKey(), globalvar.getValue()));

        List<Variable> variables = variableRepository.findByWorkspace(workspace).orElse(new ArrayList<>());
        variables.forEach(variable -> VARS.put(variable.getKey(), variable.getValue()));
    }

    public String replaceVars(String input) {
        if (input == null) {
            return null;
        }

        Pattern pattern = Pattern.compile("\\{\\{var\\.(\\w+)\\}\\}");
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String replacement = VARS.getOrDefault(matcher.group(1), matcher.group(0));
            matcher.appendReplacement(buffer, replacement);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}