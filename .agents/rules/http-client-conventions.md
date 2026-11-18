---
trigger: model_decision
description: Conventions for using WebClient, WebClient.Builder, and HTTP clients calling external services
---

# HTTP Client & WebClient Conventions

When creating or modifying HTTP clients (such as Spring WebFlux `WebClient`, `WebClient.Builder`, Feign, or SDK HTTP clients) to interact with external services, always adhere to the following rules:

## 1. WebClient.Builder Isolation in Singleton Services
* **Never mutate a shared `WebClient.Builder` field in request methods:** `DefaultWebClientBuilder` mutates headers (`initHeaders()`), filters (`initFilters()`), base URLs, and connectors in place.
* **Always `.clone()` before mutation:** If a singleton service injects `WebClient.Builder` and configures it per-request (e.g. adding headers, filters, or custom `baseUrl`), you MUST call `.clone()` first:
  ```java
  // CORRECT:
  WebClient webClient = webClientBuilder
          .clone()
          .baseUrl(apiUrl)
          .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .build();

  // INCORRECT (accumulates headers/filters across requests):
  WebClient webClient = webClientBuilder
          .baseUrl(apiUrl)
          .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .build();
  ```

## 2. Construction Timing & Immutability
* **Standardized Reactor Netty Factory (API):** In `api`, use `io.terrakube.api.plugin.http.ReactorNettyWebClientFactory` (`connector()`, `redirectingConnector()`, `compressedConnector()`) when creating `WebClient` instances so 30s response / 10s connect timeouts and 32 KiB max response header limits are enforced consistently, paired with Reactor `.timeout(...)` deadlines on blocking calls where needed (e.g., Vault login in `DynamicCredentialsService`).
* **Immutable Client per Service (Preferred when configuration is static):** If the external endpoint and headers are static for the service (e.g. `PublicRegistryProxyService`), configure and call `.build()` **once in the constructor** and store the resulting immutable `WebClient` in a `private final WebClient webClient` field.
* **Per-Request Isolation (When configuration is dynamic):** If tokens, headers, or endpoints vary per request or per workspace, use `webClientBuilder.clone()` or set headers per-request on the `RequestHeadersSpec` (`webClient.get().header(...)`).

## 3. Filters and Interceptors
* **Do NOT register dynamic `ExchangeFilterFunction`s on shared builders:** Calling `.filter(...)` on an un-cloned shared builder accumulates $2N$ filters over $N$ requests. Always isolate filters on a `.clone()` instance.
* **Feign RequestInterceptors:** In Feign clients, dynamic headers (e.g. bearer tokens) must be attached via `RequestInterceptor` to the per-request `RequestTemplate`.

## 4. Regression Testing
* Whenever modifying or creating services with `WebClient.Builder`, add a unit test asserting that multiple sequential requests against an embedded `HttpServer` (or mock) do not multiply header count or filter invocations.
