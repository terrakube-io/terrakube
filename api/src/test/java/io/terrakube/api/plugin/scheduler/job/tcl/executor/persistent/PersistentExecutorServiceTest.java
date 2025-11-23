package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;

import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import reactor.core.publisher.Mono;

public class PersistentExecutorServiceTest {

    private GlobalVarRepository globalVarRepository;
    private Builder webClientBuilder;

    private WebClient webClient;
    private RequestBodyUriSpec requestBodyUriSpec;
    private RequestBodySpec requestBodySpec;
    @SuppressWarnings("rawtypes")
    private RequestHeadersSpec requestHeadersSpec;
    private ResponseSpec responseSpec;
    private ResponseEntity<ExecutorContext> responseEntity;

    @SuppressWarnings("unchecked")
    @BeforeEach
    private void setup() {
        globalVarRepository = mock(GlobalVarRepository.class, new FailUnkownMethod<>());
        doReturn(Optional.ofNullable(null)).when(globalVarRepository).findByOrganizationAndKey(any(), any());

        webClientBuilder = mock(WebClient.Builder.class, new FailUnkownMethod<>());
        webClient = mock(WebClient.class, new FailUnkownMethod<>());
        requestBodyUriSpec = mock(RequestBodyUriSpec.class, new FailUnkownMethod<>());
        requestBodySpec = mock(RequestBodySpec.class, new FailUnkownMethod<>());
        requestBodySpec = mock(RequestBodySpec.class, new FailUnkownMethod<>());
        requestHeadersSpec = mock(RequestHeadersSpec.class, new FailUnkownMethod<>());
        responseSpec = mock(ResponseSpec.class, new FailUnkownMethod<>());
        responseEntity = mock(ResponseEntity.class, new FailUnkownMethod<>());

        doReturn(webClientBuilder).when(webClientBuilder).clientConnector(any());
        doReturn(webClient).when(webClientBuilder).build();
        doReturn(requestBodyUriSpec).when(webClient).post();
        doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
        doReturn(requestBodySpec).when(requestBodySpec).contentType(any(MediaType.class));
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any(ExecutorContext.class));
        doReturn(responseSpec).when(requestHeadersSpec).retrieve();
        doReturn(Mono.just(responseEntity)).when(responseSpec).toEntity(ExecutorContext.class);
    }

    private PersistentExecutorService subject() {
        return new PersistentExecutorService(
            "http://default-executor/",
            globalVarRepository,
            webClientBuilder
        );
    }

    private Job jobOnDefaultExecutor() {
        Organization org = new Organization();
        org.setName("ze-org");

        Workspace workspace = new Workspace();
        workspace.setLocked(false);

        Job job = new Job();
        job.setId(4711);
        job.setWorkspace(workspace);
        return job;
    }

    private Job jobOnAgent() {
        Agent agent = new Agent();
        agent.setUrl("http://ze-agent/");

        Job job = jobOnDefaultExecutor();
        job.getWorkspace().setAgent(agent);
        return job;
    }

    private ExecutorContext context() {
        return ExecutorContext.builder()
                .branch("ze-branch")
                .organizationId("ze-org")
                .workspaceId("ze-workspace")
                .environmentVariables(new HashMap<>())
                .build();
    }

    private ExecutorContext response() {
        return ExecutorContext.builder().build();
    }

    @Test
    public void postsToDefaultExecutor() throws ExecutionException {
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();
        doReturn(response()).when(responseEntity).getBody();

        subject().send(jobOnDefaultExecutor(), context());

        verify(requestBodyUriSpec).uri("http://default-executor/");
        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void propagatesMalformedUri() throws ExecutionException {
        Globalvar executorUrl = new Globalvar();
        executorUrl.setValue("http:// /");
        doReturn(Optional.of(executorUrl)).when(globalVarRepository).findByOrganizationAndKey(any(), any());

        assertThrows(ExecutionException.class, () -> subject().send(jobOnDefaultExecutor(), context()));

        verify(requestHeadersSpec, times(0)).retrieve();
    }

    @Test
    public void propagatesHttpFailures() throws ExecutionException {
        doReturn(HttpStatus.BAD_REQUEST).when(responseEntity).getStatusCode();
        doReturn(response()).when(responseEntity).getBody();

        assertThrows(ExecutionException.class, () -> subject().send(jobOnDefaultExecutor(), context()));

        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void postsToConfiguredExecutor() throws ExecutionException, URISyntaxException {
        Globalvar executorUrl = new Globalvar();
        executorUrl.setValue("http://ze-executor/");
        doReturn(Optional.of(executorUrl)).when(globalVarRepository).findByOrganizationAndKey(any(), any());
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();
        doReturn(response()).when(responseEntity).getBody();

        subject().send(jobOnDefaultExecutor(), context());

        verify(requestBodyUriSpec).uri("http://ze-executor/api/v1/terraform-rs");
        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void postsToAgent() throws ExecutionException {
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();
        doReturn(response()).when(responseEntity).getBody();

        subject().send(jobOnAgent(), context());

        verify(requestBodyUriSpec).uri("http://ze-agent/api/v1/terraform-rs");
        verify(requestHeadersSpec, times(1)).retrieve();
    }
}