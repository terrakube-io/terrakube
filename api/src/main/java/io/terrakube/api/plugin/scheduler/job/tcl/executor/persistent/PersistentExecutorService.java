package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.netty.channel.ChannelOption;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorUnavailableException;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import javax.crypto.SecretKey;

@Slf4j
@Service
public class PersistentExecutorService {

    @Value("${io.terrakube.executor.url}")
    private String executorUrl;

    @Autowired
    private GlobalVarRepository globalVarRepository;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${io.terrakube.token.internal}")
    private String base64KeyInternal;

    // Manual all-args constructor because Lombok will not copy @Value
    public PersistentExecutorService(
        @Value("${io.terrakube.executor.url}") String executorUrl,
        @Autowired GlobalVarRepository globalVarRepository,
        @Autowired WebClient.Builder webClientBuilder,
        @Value("${io.terrakube.token.internal}") String internalJwtSecret) {
            this.executorUrl = executorUrl;
            this.globalVarRepository = globalVarRepository;
            this.webClientBuilder = webClientBuilder;
            this.base64KeyInternal = internalJwtSecret;
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    public void send(Job job, ExecutorContext executorContext) throws ExecutionException {
        HttpClient httpClient = HttpClient.create()
                .proxyWithSystemProperties()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);

        WebClient webClient = webClientBuilder
                .clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        String executorUrlForRequest;
        try {
            executorUrlForRequest = getExecutorUrl(job);
        } catch (URISyntaxException e) {
            throw new ExecutionException(e);
        }

        // The executor answers POST /api/v1/terraform-rs with 202 Accepted the moment it has
        // queued the job for asynchronous execution. Its response body is NOT an API/executor
        // compatibility contract - deserializing it as ExecutorContext once meant a completed
        // run could be marked failed just because the acknowledgement body was shaped for a
        // different executor version. Read a bodyless entity: dispatch success is the 202 alone.
        ResponseEntity<Void> response = null;
        try {
            response = webClient.post()
                    .uri(executorUrlForRequest)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + generateSystemToken())
                    .bodyValue(executorContext)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception ex) {
            if (ex instanceof WebClientRequestException) {
                // No response was ever received: connection refused, timed out, or (in Kubernetes,
                // when every replica is mid-job and REFUSING_TRAFFIC) the Service has no ready
                // endpoints. This is a capacity problem, not a broken job, so it's retryable.
                String hint = String.format(
                        " Cannot connect to executor at %s. Check that the executor is running and reachable (io.terrakube.executor.url / AzBuilderExecutorUrl).",
                        executorUrlForRequest);
                throw new ExecutorUnavailableException(new Throwable(ex.getMessage() + hint, ex));
            }
            if (ex instanceof WebClientResponseException wcre && isRetryableGatewayStatus(wcre.getStatusCode())) {
                // 503: the executor pod's per-pod capacity gate was already held by another job
                // (persistent-executor-admission-control). 502/504: a proxy/ingress between the
                // API and the executor dropped or timed out the upstream connection - which can
                // happen *after* the executor already accepted the job (its response, not the
                // request, was lost). None of these mean the job is broken, and failing it here
                // fires a spurious "failed" notification for a run the executor goes on to
                // finish - so treat them all as retryable capacity/transport problems.
                throw new ExecutorUnavailableException(new Throwable(
                        "Executor at " + executorUrlForRequest + " returned " + wcre.getStatusCode().value()
                                + ", will retry", ex));
            }
            throw new ExecutionException(new Throwable(ex.getMessage(), ex));
        }

        log.debug("Sending Job: /n {}", executorContext.toBuilder()
                .accessToken("****")
                .moduleSshKey("****")
                .build());
        log.info("Response Status: {}", response.getStatusCode().value());

        if (!response.getStatusCode().equals(HttpStatus.ACCEPTED)) {
            String message = String.format("Executor error status %s", response.getStatusCode());
            throw new ExecutionException(new Throwable(message));
        }
    }

    // 503 (per-pod admission control) plus the two proxy/ingress statuses that show up when the
    // hop between the API and the executor fails rather than the executor itself - retryable
    // capacity/transport problems, not a broken job.
    private static boolean isRetryableGatewayStatus(HttpStatusCode status) {
        return status.equals(HttpStatus.SERVICE_UNAVAILABLE)
                || status.equals(HttpStatus.BAD_GATEWAY)
                || status.equals(HttpStatus.GATEWAY_TIMEOUT);
    }

    private String getExecutorUrl(Job job) throws URISyntaxException {
        String agentUrl = job.getWorkspace().getAgent() != null
                ? job.getWorkspace().getAgent().getUrl() + "/api/v1/terraform-rs"
                : validateDefaultExecutor(job);
        log.info("Job {} Executor agent url: {}", job.getId(), agentUrl);
        return new URI(agentUrl).normalize().toString();
    }

    private String validateDefaultExecutor(Job job) {
        Optional<Globalvar> executor = globalVarRepository.findByOrganizationAndKey(job.getOrganization(),
                "TERRAKUBE_DEFAULT_EXECUTOR");
        if (executor.isPresent()) {
            log.info("Found executor url {}", executor.get().getValue());
            return executor.get().getValue() + "/api/v1/terraform-rs";
        } else {
            log.info("No default executor found, using default executor url {}", this.executorUrl);
            return this.executorUrl;
        }
    }

    public String generateSystemToken() {
        return Jwts.builder()
                .issuer("TERRAKUBE_INTERNAL")
                .subject(String.format("%s (Token)", "Terrakube Internal"))
                .audience().add("TERRAKUBE_INTERNAL").and()
                .id(UUID.randomUUID().toString())
                .claim("email", "internal@terrakube.io")
                .claim("email_verified", true)
                .claim("name", "Terrakube Api")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(
                        Instant.now().plus(10, ChronoUnit.SECONDS)
                        )
                ).signWith(Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(this.base64KeyInternal))).compact();
    }
}
