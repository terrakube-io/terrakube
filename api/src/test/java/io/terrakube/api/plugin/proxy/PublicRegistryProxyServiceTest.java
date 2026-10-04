package io.terrakube.api.plugin.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicRegistryProxyServiceTest {

    private final List<ClientRequest> requests = new ArrayList<>();

    // An explicit exchange function takes precedence over the service's Reactor Netty connector,
    // so no request leaves the JVM.
    private PublicRegistryProxyService serviceRespondingWith(HttpStatus status, String body) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
        return new PublicRegistryProxyService(builder);
    }

    @Test
    void getProviderWithoutVersionRequestsLatestDetails() {
        String body = "{\"namespace\":\"cloudflare\",\"name\":\"cloudflare\",\"version\":\"5.26.0\"}";
        PublicRegistryProxyService service = serviceRespondingWith(HttpStatus.OK, body);

        ResponseEntity<String> response = service.getProvider("cloudflare", "cloudflare", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(body, response.getBody());
        assertEquals("https://registry.terraform.io/v1/providers/cloudflare/cloudflare",
                requests.get(0).url().toString());
    }

    @Test
    void getProviderWithVersionRequestsThatVersion() {
        PublicRegistryProxyService service = serviceRespondingWith(HttpStatus.OK, "{\"version\":\"4.52.0\"}");

        ResponseEntity<String> response = service.getProvider("cloudflare", "cloudflare", "4.52.0");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("https://registry.terraform.io/v1/providers/cloudflare/cloudflare/4.52.0",
                requests.get(0).url().toString());
    }

    @Test
    void getProviderPassesRegistryNotFoundThrough() {
        String body = "{\"errors\":[\"Not Found\"]}";
        PublicRegistryProxyService service = serviceRespondingWith(HttpStatus.NOT_FOUND, body);

        ResponseEntity<String> response = service.getProvider("nobody", "nothing", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(body, response.getBody());
        // 404 is not retried.
        assertEquals(1, requests.size());
    }

    @Test
    void getProviderVersionsPassesRegistryNotFoundThrough() {
        PublicRegistryProxyService service = serviceRespondingWith(HttpStatus.NOT_FOUND, "{\"errors\":[\"Not Found\"]}");

        ResponseEntity<String> response = service.getProviderVersions("nobody", "nothing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("https://registry.terraform.io/v1/providers/nobody/nothing/versions",
                requests.get(0).url().toString());
    }
}
