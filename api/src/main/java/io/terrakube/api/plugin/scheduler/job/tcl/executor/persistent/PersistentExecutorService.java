package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

@Slf4j
@Service
public class PersistentExecutorService {

    @Value("${io.terrakube.executor.url}")
    private String executorUrl;

    @Autowired
    private GlobalVarRepository globalVarRepository;

    @Autowired
    private WebClient.Builder webClientBuilder;

    // Manual all-args constructor because Lombok will not copy @Value
    public PersistentExecutorService(
        @Value("${io.terrakube.executor.url}") String executorUrl,
        @Autowired GlobalVarRepository globalVarRepository,
        @Autowired WebClient.Builder webClientBuilder) {
            this.executorUrl = executorUrl;
            this.globalVarRepository = globalVarRepository;
            this.webClientBuilder = webClientBuilder;
    }

    public void send(Job job, ExecutorContext executorContext) throws ExecutionException {
        WebClient webClient = webClientBuilder
                .clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create().proxyWithSystemProperties()))
                .build();

        ResponseEntity<ExecutorContext> response = null;
        try {
            response = webClient.post()
                    .uri(getExecutorUrl(job))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(executorContext)
                    .retrieve()
                    .toEntity(ExecutorContext.class)
                    .block();
        } catch (Exception ex) {
            throw new ExecutionException(ex);
        }

        log.debug("Sending Job: /n {}", executorContext.toBuilder()
                .accessToken("****")
                .moduleSshKey("****")
                .build());
        log.info("Response Status: {}", response.getStatusCode().value());

        if (!response.getStatusCode().equals(HttpStatus.ACCEPTED)) {
            String message = String.format(
                    "Executor error status %s: %s",
                    response.getStatusCode(),
                    response.getBody());
            throw new ExecutionException(new Throwable(message));
        }
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
}
