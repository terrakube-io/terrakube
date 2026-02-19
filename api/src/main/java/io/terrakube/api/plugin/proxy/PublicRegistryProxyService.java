package io.terrakube.api.plugin.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PublicRegistryProxyService {

    private static final String TERRAFORM_REGISTRY_BASE_URL = "https://registry.terraform.io";
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PublicRegistryProxyService(WebClient.Builder webClientBuilder) {
        this.objectMapper = new ObjectMapper();

        HttpClient httpClient = HttpClient.create()
                .compress(true)
                .proxyWithSystemProperties()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        this.webClient = webClientBuilder
                .baseUrl(TERRAFORM_REGISTRY_BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024)) // 16MB buffer for large provider responses
                .defaultHeaders(h -> {
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                    h.add("User-Agent", "Terrakube/1.0 (https://terrakube.io)");
                })
                .build();
    }

    /**
     * Search providers in Terraform Registry using the v2 API.
     * Uses GET /v2/providers?filter[name]={query}&page[size]={limit} which provides
     * accurate name-based filtering (the v1 search endpoint returns unreliable results).
     * The v2 JSON:API response is transformed into the v1-compatible format expected by the frontend.
     */
    public ResponseEntity<String> searchProviders(String query, int limit) {
        log.info("Searching Terraform Registry providers: query={}, limit={}", query, limit);
        
        try {
            String v2Response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/providers")
                            .queryParam("filter[name]", query)
                            .queryParam("page[size]", limit)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofMillis(500))
                            .filter(throwable -> !(throwable instanceof WebClientResponseException.NotFound)))
                    .block(Duration.ofSeconds(30));
            
            String transformedResponse = transformV2ProvidersToV1(v2Response);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(transformedResponse);
        } catch (Exception e) {
            log.error("Error searching providers: ", e);
            return ResponseEntity.internalServerError()
                    .body("{\"error\": \"Failed to search providers: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Transforms a v2 JSON:API provider search response into the v1-compatible format.
     * 
     * v2 format: { "data": [{ "type": "providers", "id": "...", "attributes": { "name": "...", ... } }], "meta": { "pagination": { ... } } }
     * v1 format: { "providers": [{ "id": "...", "namespace": "...", "name": "...", ... }], "meta": { "limit": ..., ... } }
     */
    private String transformV2ProvidersToV1(String v2Json) throws Exception {
        JsonNode v2Root = objectMapper.readTree(v2Json);
        JsonNode dataArray = v2Root.path("data");

        ObjectNode v1Root = objectMapper.createObjectNode();
        ArrayNode providersArray = objectMapper.createArrayNode();

        if (dataArray.isArray()) {
            for (JsonNode item : dataArray) {
                JsonNode attrs = item.path("attributes");
                ObjectNode provider = objectMapper.createObjectNode();

                String namespace = attrs.path("namespace").asText("");
                String name = attrs.path("name").asText("");
                provider.put("id", namespace + "/" + name);
                provider.put("namespace", namespace);
                provider.put("name", name);
                provider.putNull("alias");
                provider.put("version", "");
                provider.put("tag", "");
                provider.put("description", attrs.path("description").asText(""));
                provider.put("source", attrs.path("source").asText(""));
                provider.put("published_at", "");
                provider.put("downloads", attrs.path("downloads").asLong(0));
                provider.put("tier", attrs.path("tier").asText("community"));

                String logoUrl = attrs.path("logo-url").asText("");
                if (!logoUrl.isEmpty() && logoUrl.startsWith("/")) {
                    logoUrl = TERRAFORM_REGISTRY_BASE_URL + logoUrl;
                }
                provider.put("logo_url", logoUrl);

                providersArray.add(provider);
            }
        }

        v1Root.set("providers", providersArray);

        // Transform pagination metadata
        ObjectNode meta = objectMapper.createObjectNode();
        JsonNode v2Meta = v2Root.path("meta").path("pagination");
        int pageSize = v2Meta.path("page-size").asInt(20);
        int currentPage = v2Meta.path("current-page").asInt(1);
        meta.put("limit", pageSize);
        meta.put("current_offset", (currentPage - 1) * pageSize);
        Integer nextPage = v2Meta.has("next-page") && !v2Meta.path("next-page").isNull()
                ? v2Meta.path("next-page").asInt() : null;
        if (nextPage != null) {
            meta.put("next_offset", (nextPage - 1) * pageSize);
        } else {
            meta.putNull("next_offset");
        }
        meta.putNull("next_url");
        v1Root.set("meta", meta);

        return objectMapper.writeValueAsString(v1Root);
    }

    /**
     * Search modules in Terraform Registry
     * GET /v1/modules/search?q={query}&limit={limit}
     */
    public ResponseEntity<String> searchModules(String query, int limit) {
        log.info("Searching Terraform Registry modules: query={}, limit={}", query, limit);
        
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/modules/search")
                            .queryParam("q", query)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofMillis(500))
                            .filter(throwable -> !(throwable instanceof WebClientResponseException.NotFound)))
                    .block(Duration.ofSeconds(30));
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("Error searching modules: ", e);
            return ResponseEntity.internalServerError()
                    .body("{\"error\": \"Failed to search modules: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Get provider versions from Terraform Registry
     * GET /v1/providers/{namespace}/{name}/versions
     */
    public ResponseEntity<String> getProviderVersions(String namespace, String name) {
        log.info("Getting provider versions: namespace={}, name={}", namespace, name);
        
        try {
            String response = webClient.get()
                    .uri("/v1/providers/{namespace}/{name}/versions", namespace, name)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofMillis(500))
                            .filter(throwable -> !(throwable instanceof WebClientResponseException.NotFound)))
                    .block(Duration.ofSeconds(30));
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("Error getting provider versions: ", e);
            return ResponseEntity.internalServerError()
                    .body("{\"error\": \"Failed to get provider versions: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Get provider download info from Terraform Registry
     * GET /v1/providers/{namespace}/{name}/{version}/download/{os}/{arch}
     */
    public ResponseEntity<String> getProviderDownload(String namespace, String name, String version, String os, String arch) {
        log.info("Getting provider download: namespace={}, name={}, version={}, os={}, arch={}", 
                namespace, name, version, os, arch);
        
        try {
            String response = webClient.get()
                    .uri("/v1/providers/{namespace}/{name}/{version}/download/{os}/{arch}", 
                            namespace, name, version, os, arch)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofMillis(500))
                            .filter(throwable -> !(throwable instanceof WebClientResponseException.NotFound)))
                    .block(Duration.ofSeconds(30));
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("Error getting provider download info: ", e);
            return ResponseEntity.internalServerError()
                    .body("{\"error\": \"Failed to get provider download info: " + e.getMessage() + "\"}");
        }
    }

}
