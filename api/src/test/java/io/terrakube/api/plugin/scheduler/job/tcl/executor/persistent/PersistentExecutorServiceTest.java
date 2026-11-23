package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;

import io.terrakube.api.helpers.FailUnkownMethod;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.reactive.function.client.WebClientRequestException;
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
        return subject("http://default-executor/");
    }

    private PersistentExecutorService subject(String executorUrl) {
        PersistentExecutorService persistentExecutorService = spy(new PersistentExecutorService(
                executorUrl,
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

        ExecutorUnavailableException ex = assertThrows(ExecutorUnavailableException.class,
                () -> subject().send(jobOnDefaultExecutor(), context()));

        assertFalse(ex.mayHaveBeenAccepted());
        verify(requestHeadersSpec, times(1)).retrieve();
    }

    @Test
    public void badGatewayResponseBecomesExecutorUnavailableException() {
        // A proxy/ingress between the API and the executor dropped the upstream connection -
        // possibly after the executor already accepted the job. Retryable, not a job failure.
        WebClientResponseException badGateway = WebClientResponseException.create(
                502, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], null);
        doReturn(Mono.error(badGateway)).when(responseSpec).toBodilessEntity();

        ExecutorUnavailableException ex = assertThrows(ExecutorUnavailableException.class,
                () -> subject().send(jobOnDefaultExecutor(), context()));
        assertTrue(ex.mayHaveBeenAccepted());
    }

    @Test
    public void gatewayTimeoutResponseBecomesExecutorUnavailableException() {
        WebClientResponseException gatewayTimeout = WebClientResponseException.create(
                504, "Gateway Timeout", HttpHeaders.EMPTY, new byte[0], null);
        doReturn(Mono.error(gatewayTimeout)).when(responseSpec).toBodilessEntity();

        ExecutorUnavailableException ex = assertThrows(ExecutorUnavailableException.class,
                () -> subject().send(jobOnDefaultExecutor(), context()));
        assertTrue(ex.mayHaveBeenAccepted());
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

    // The ClusterIP of the chart's executor Service is a private address, and the operator is who set it.
    @Test
    public void sendsToTheExecutorUrlOfTheOperatorOnAPrivateNetwork() throws ExecutionException {
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();
        PersistentExecutorService subject = subject("http://10.96.0.12:8090/api/v1/terraform-rs");
        subject.setAgentUrlValidator(new AgentUrlValidator(true));

        subject.send(jobOnDefaultExecutor(), context());

        verify(requestBodyUriSpec).uri("http://10.96.0.12:8090/api/v1/terraform-rs");
    }

    @Test
    public void blocksAnAgentOnAPrivateNetwork() {
        PersistentExecutorService subject = subject();
        subject.setAgentUrlValidator(new AgentUrlValidator(true));
        Job job = jobOnAgent();
        job.getWorkspace().getAgent().setUrl("http://10.96.0.12:8090");

        assertThrows(ExecutionException.class, () -> subject.send(job, context()));
        verify(requestBodyUriSpec, never()).uri(anyString());
    }

    @Test
    public void blocksTheDefaultExecutorAnOrganizationSetsOnAPrivateNetwork() {
        Globalvar executorUrl = new Globalvar();
        executorUrl.setValue("http://10.96.0.12:8090");
        doReturn(Optional.of(executorUrl)).when(globalVarRepository).findByOrganizationAndKey(any(), any());
        PersistentExecutorService subject = subject();
        subject.setAgentUrlValidator(new AgentUrlValidator(true));

        assertThrows(ExecutionException.class, () -> subject.send(jobOnDefaultExecutor(), context()));
        verify(requestBodyUriSpec, never()).uri(anyString());
    }

    @Test
    public void generateSystemTokenPinsExpectedExecutorDispatchClaims() {
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "terrakube-internal-dispatch-secret-at-least-64-bytes-long-for-hmac!".getBytes(StandardCharsets.UTF_8));
        PersistentExecutorService realService = new PersistentExecutorService(
                "http://default-executor/",
                globalVarRepository,
                webClientBuilder,
                secret);

        String token = realService.generateSystemToken();
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret)))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals("TERRAKUBE_INTERNAL", claims.getIssuer());
        assertEquals("Terrakube Internal (Token)", claims.getSubject());
        assertTrue(claims.getAudience() != null && claims.getAudience().contains("TERRAKUBE_INTERNAL"));
        assertNull(claims.get("workspaceId"));
    }

    @Test
    public void classifiesPreConnectVsPostSendFailures() throws Exception {
        URI uri = new URI("http://default-executor/api/v1/terraform-rs");
        WebClientRequestException refused = new WebClientRequestException(
                new ConnectException("Connection refused"), HttpMethod.POST, uri, HttpHeaders.EMPTY);
        doReturn(Mono.error(refused)).when(responseSpec).toBodilessEntity();
        assertFalse(assertThrows(ExecutorUnavailableException.class,
                () -> subject().send(jobOnDefaultExecutor(), context())).mayHaveBeenAccepted());

        WebClientRequestException readTimedOut = new WebClientRequestException(
                ReadTimeoutException.INSTANCE, HttpMethod.POST, uri, HttpHeaders.EMPTY);
        doReturn(Mono.error(readTimedOut)).when(responseSpec).toBodilessEntity();
        assertTrue(assertThrows(ExecutorUnavailableException.class,
                () -> subject().send(jobOnDefaultExecutor(), context())).mayHaveBeenAccepted());
    }

    @Test
    public void treatsTruncated202ResponseBodyAsAcceptedDispatch() throws Exception {
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "terrakube-internal-dispatch-secret-at-least-64-bytes-long-for-hmac!".getBytes(StandardCharsets.UTF_8));
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (java.net.Socket client = server.accept()) {
                    java.io.InputStream in = client.getInputStream();
                    byte[] buf = new byte[4096];
                    in.read(buf);
                    java.io.OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 202 Accepted\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n"
                            + "10\r\n{\"jobId\":\"71\"").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    client.setSoLinger(true, 0);
                } catch (Exception ignored) {
                }
            });
            responder.setDaemon(true);
            responder.start();

            PersistentExecutorService realService = new PersistentExecutorService(
                    "http://127.0.0.1:" + server.getLocalPort() + "/api/v1/terraform-rs",
                    globalVarRepository,
                    WebClient.builder(),
                    secret);

            realService.send(jobOnDefaultExecutor(), context());
        }
    }

    @Test
    public void outerBlockSafetyDeadlineBoundsStuckCallAndMarksMayHaveBeenAccepted() {
        doReturn(Mono.never()).when(responseSpec).toBodilessEntity();
        PersistentExecutorService service = subject();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "responseTimeout", java.time.Duration.ofMillis(40));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "blockSlackTimeout", java.time.Duration.ofMillis(40));

        ExecutorUnavailableException ex = assertThrows(ExecutorUnavailableException.class,
                () -> service.send(jobOnDefaultExecutor(), context()));
        assertTrue(ex.mayHaveBeenAccepted());
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    public void sequentialDispatchesCloneSharedWebClientBuilder() throws Exception {
        doReturn(HttpStatus.ACCEPTED).when(responseEntity).getStatusCode();
        PersistentExecutorService service = subject();
        service.send(jobOnDefaultExecutor(), context());
        service.send(jobOnDefaultExecutor(), context());
        verify(webClientBuilder, times(2)).clone();
    }
}
