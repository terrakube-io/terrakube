package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Optional;

import io.terrakube.api.helpers.FailUnkownMethod;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorUnavailableException;
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
    private ResponseEntity<Void> responseEntity;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
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

        doReturn(webClientBuilder).when(webClientBuilder).clone();
        doReturn(webClientBuilder).when(webClientBuilder).clientConnector(any());
        doReturn(webClient).when(webClientBuilder).build();
        doReturn(requestBodyUriSpec).when(webClient).post();
        doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
        doReturn(requestBodySpec).when(requestBodySpec).contentType(any(MediaType.class));
        doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), anyString());
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any(ExecutorContext.class));
        doReturn(responseSpec).when(requestHeadersSpec).retrieve();
        doReturn(Mono.just(responseEntity)).when(responseSpec).toBodilessEntity();
    }

    private PersistentExecutorService subject() {
        PersistentExecutorService persistentExecutorService = spy(new PersistentExecutorService(
                "http://default-executor/",
                globalVarRepository,
                webClientBuilder,
                RandomStringUtils.randomAlphanumeric(32)));
        doReturn(RandomStringUtils.randomAlphanumeric(64)).when(persistentExecutorService).generateSystemToken();
        return persistentExecutorService;
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

    @Test
    public void postsToDefaultExecutor() throws ExecutionException {
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();

        subject().send(jobOnDefaultExecutor(), context());

        verify(requestBodyUriSpec).uri("http://default-executor/");
        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void acknowledgementBodyIsNeverDeserialized() throws ExecutionException {
        // toEntity(ExecutorContext.class) is deliberately NOT stubbed. A regression to reading a
        // typed body makes that unstubbed call throw via FailUnkownMethod and fails this test,
        // protecting the "202 is a bodyless acknowledgement" contract without depending on any
        // particular executor response body.
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();

        subject().send(jobOnDefaultExecutor(), context());

        verify(responseSpec, times(1)).toBodilessEntity();
        verify(responseSpec, never()).toEntity(ExecutorContext.class);
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

        assertThrows(ExecutionException.class, () -> subject().send(jobOnDefaultExecutor(), context()));

        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void busyExecutorResponseBecomesExecutorUnavailableException() {
        WebClientResponseException busy = WebClientResponseException.create(
                503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null);
        doReturn(Mono.error(busy)).when(responseSpec).toBodilessEntity();

        assertThrows(ExecutorUnavailableException.class, () -> subject().send(jobOnDefaultExecutor(), context()));

        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void badGatewayResponseBecomesExecutorUnavailableException() {
        // A proxy/ingress between the API and the executor dropped the upstream connection -
        // possibly after the executor already accepted the job. Retryable, not a job failure.
        WebClientResponseException badGateway = WebClientResponseException.create(
                502, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], null);
        doReturn(Mono.error(badGateway)).when(responseSpec).toBodilessEntity();

        assertThrows(ExecutorUnavailableException.class, () -> subject().send(jobOnDefaultExecutor(), context()));
    }

    @Test
    public void gatewayTimeoutResponseBecomesExecutorUnavailableException() {
        WebClientResponseException gatewayTimeout = WebClientResponseException.create(
                504, "Gateway Timeout", HttpHeaders.EMPTY, new byte[0], null);
        doReturn(Mono.error(gatewayTimeout)).when(responseSpec).toBodilessEntity();

        assertThrows(ExecutorUnavailableException.class, () -> subject().send(jobOnDefaultExecutor(), context()));
    }

    @Test
    public void otherHttpErrorResponseStaysAHardExecutionException() {
        WebClientResponseException serverError = WebClientResponseException.create(
                500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null);
        doReturn(Mono.error(serverError)).when(responseSpec).toBodilessEntity();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> subject().send(jobOnDefaultExecutor(), context()));
        // not the retryable subclass
        org.junit.jupiter.api.Assertions.assertFalse(thrown instanceof ExecutorUnavailableException);
    }

    @Test
    public void postsToConfiguredExecutor() throws ExecutionException, URISyntaxException {
        Globalvar executorUrl = new Globalvar();
        executorUrl.setValue("http://ze-executor/");
        doReturn(Optional.of(executorUrl)).when(globalVarRepository).findByOrganizationAndKey(any(), any());
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();

        subject().send(jobOnDefaultExecutor(), context());

        verify(requestBodyUriSpec).uri("http://ze-executor/api/v1/terraform-rs");
        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void postsToAgent() throws ExecutionException {
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();

        subject().send(jobOnAgent(), context());

        verify(requestBodyUriSpec).uri("http://ze-agent/api/v1/terraform-rs");
        verify(requestHeadersSpec, times(1)).retrieve();
    }
}