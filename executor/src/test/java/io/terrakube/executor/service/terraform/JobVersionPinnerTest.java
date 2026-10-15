package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class JobVersionPinnerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private JobVersionPinner pinner;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        WorkspaceSecurity security = Mockito.mock(WorkspaceSecurity.class);
        when(security.generateAccessToken(1)).thenReturn("token");
        pinner = new JobVersionPinner(security, mapper, "http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void putsTheResolvedVersionOnTheJob() throws Exception {
        assertTrue(pinner.pin("org-1", "42", "1.16.5"));

        assertEquals(List.of("PUT /version/v1/organization/org-1/job/42 Bearer token application/json"), requests);
        assertEquals("1.16.5", mapper.readTree(bodies.get(0)).get("version").asText());
    }

    @Test
    void aDifferentVersionTheJobAlreadyHasIsNotAFailure() {
        status.set(409);

        assertTrue(pinner.pin("org-1", "42", "1.16.5"));
        assertEquals(1, requests.size(), "a refusal is final and is not retried");
    }

    @Test
    void aRejectionThatCannotChangeIsNotRetried() {
        status.set(403);

        assertFalse(pinner.pin("org-1", "42", "1.16.5"));
        assertEquals(1, requests.size());
    }

    @Test
    void retriesATransientFailureAndGivesUpWithoutThrowing() {
        status.set(503);

        assertFalse(pinner.pin("org-1", "42", "1.16.5"));
        assertEquals(3, requests.size());
    }

    @Test
    void anUnreachableApiNeverThrows() {
        server.stop(0);

        assertFalse(pinner.pin("org-1", "42", "1.16.5"));
    }
}
