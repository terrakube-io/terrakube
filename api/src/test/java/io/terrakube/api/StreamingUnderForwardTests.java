package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * Live log streams (SSE, async dispatch) and the GraphQL WebSocket must work when reached through
 * the canonical /api prefix, which forwards before Spring Security.
 */
@TestPropertySource(properties = {
    "io.terrakube.public-url=https://app.example.com",
    "io.terrakube.hostname="
})
class StreamingUnderForwardTests extends ServerApplicationTests {

    @ParameterizedTest
    @ValueSource(strings = {"", "/api"})
    void outputStreamWorksWithAndWithoutPrefix(String prefix) {
        Organization org = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(JobStatus.completed);
        job = jobRepository.save(job);
        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(prefix.isEmpty() ? 201 : 202);
        step.setStatus(JobStatus.completed);
        step = stepRepository.save(step);

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get(prefix + "/tfoutput/v1/organization/" + org.getId() + "/job/" + job.getId() + "/step/" + step.getId() + "/stream")
                .then()
                .statusCode(HttpStatus.OK.value())
                .contentType(startsWith("text/event-stream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/subscriptions", "/api/subscriptions"})
    void graphqlWebSocketHandshakeWorksWithAndWithoutPrefix(String path) throws Exception {
        CompletableFuture<String> firstMessage = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("graphql-transport-ws")
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create("ws://localhost:" + port + path), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        firstMessage.complete(data.toString());
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);
        socket.sendText("{\"type\":\"connection_init\",\"payload\":{}}", true).get(10, TimeUnit.SECONDS);
        String reply = firstMessage.get(10, TimeUnit.SECONDS);
        socket.abort();
        assertTrue(reply.contains("connection_ack") || reply.contains("connection_error"),
                "unexpected first message on " + path + ": " + reply);
    }
}
