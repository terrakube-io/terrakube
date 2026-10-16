package io.terrakube.executor.plugin.tfstate.api;

import io.terrakube.executor.service.workspace.security.RunnerTokenHolder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
public class RunnerApiClient {

    public static final String RUNNER_PATH = "/tfstate/v1/runner";

    private final String apiUrl;
    private final HttpClient httpClient;

    public RunnerApiClient(String apiUrl) {
        this(apiUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    RunnerApiClient(String apiUrl, HttpClient httpClient) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.httpClient = httpClient;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String url(String path) {
        return apiUrl + RUNNER_PATH + path;
    }

    public void put(String path, byte[] body) {
        send(request(url(path)).header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    public void postJson(String path, byte[] json) {
        send(request(url(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json)).build());
    }

    public void delete(String path) {
        send(request(url(path)).DELETE().build());
    }

    public byte[] get(String absoluteUrl) {
        HttpResponse<byte[]> response = send(request(absoluteUrl).GET().build());
        return response.body();
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + RunnerTokenHolder.require());
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(String.format("%s %s returned %d", request.method(),
                        request.uri().getPath(), response.statusCode()));
            }
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
