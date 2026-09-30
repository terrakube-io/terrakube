package io.terrakube.registry;

import org.apache.http.HttpStatus;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.terrakube.registry.configuration.CacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

public class ProviderTests extends OpenRegistryApplicationTests{

    private static final String GRAPHQL_ENDPOINT="/graphql/api/v1";
    private static final String PROVIDER_SEARCH_VERSION="{\n" +
            "    \"data\": {\n" +
            "        \"organization\": {\n" +
            "            \"edges\": [\n" +
            "                {\n" +
            "                    \"node\": {\n" +
            "                        \"id\": \"d9b58bd3-f3fc-4056-a026-1163297e80a8\",\n" +
            "                        \"name\": \"simple\",\n" +
            "                        \"provider\": {\n" +
            "                            \"edges\": [\n" +
            "                                {\n" +
            "                                    \"node\": {\n" +
            "                                        \"id\": \"ccde2641-b998-4ffe-8a67-bd434ba4b00a\",\n" +
            "                                        \"name\": \"random\",\n" +
            "                                        \"version\": {\n" +
            "                                            \"edges\": [\n" +
            "                                                {\n" +
            "                                                    \"node\": {\n" +
            "                                                        \"id\": \"76a3f378-895f-45f1-85f1-37d9a360f311\",\n" +
            "                                                        \"versionNumber\": \"3.0.1\",\n" +
            "                                                        \"protocols\": \"5.0\",\n" +
            "                                                        \"implementation\": {\n" +
            "                                                            \"edges\": [\n" +
            "                                                                {\n" +
            "                                                                    \"node\": {\n" +
            "                                                                        \"id\": \"8c7fd5f4-d43f-4395-9b92-89c1dbcf6927\",\n" +
            "                                                                        \"os\": \"linux\",\n" +
            "                                                                        \"arch\": \"amd64\"\n" +
            "                                                                    }\n" +
            "                                                                }\n" +
            "                                                            ]\n" +
            "                                                        }\n" +
            "                                                    }\n" +
            "                                                }\n" +
            "                                            ]\n" +
            "                                        }\n" +
            "                                    }\n" +
            "                                }\n" +
            "                            ]\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n" +
            "            ]\n" +
            "        }\n" +
            "    }\n" +
            "}";
    private static final String PROVIDER_SEARCH_IMPLEMENTATION="{\n" +
            "    \"data\": {\n" +
            "        \"organization\": {\n" +
            "            \"edges\": [\n" +
            "                {\n" +
            "                    \"node\": {\n" +
            "                        \"id\": \"d9b58bd3-f3fc-4056-a026-1163297e80a8\",\n" +
            "                        \"name\": \"simple\",\n" +
            "                        \"provider\": {\n" +
            "                            \"edges\": [\n" +
            "                                {\n" +
            "                                    \"node\": {\n" +
            "                                        \"id\": \"ccde2641-b998-4ffe-8a67-bd434ba4b00a\",\n" +
            "                                        \"name\": \"random\",\n" +
            "                                        \"version\": {\n" +
            "                                            \"edges\": [\n" +
            "                                                {\n" +
            "                                                    \"node\": {\n" +
            "                                                        \"id\": \"76a3f378-895f-45f1-85f1-37d9a360f311\",\n" +
            "                                                        \"versionNumber\": \"3.0.1\",\n" +
            "                                                        \"protocols\": \"5.0\",\n" +
            "                                                        \"implementation\": {\n" +
            "                                                            \"edges\": [\n" +
            "                                                                {\n" +
            "                                                                    \"node\": {\n" +
            "                                                                        \"id\": \"8c7fd5f4-d43f-4395-9b92-89c1dbcf6927\",\n" +
            "                                                                        \"os\": \"linux\",\n" +
            "                                                                        \"arch\": \"amd64\",\n" +
            "                                                                        \"filename\": \"terraform-provider-random_3.0.1_linux_amd64.zip\",\n" +
            "                                                                        \"downloadUrl\": \"https://releases.hashicorp.com/terraform-provider-random/3.0.1/terraform-provider-random_3.0.1_linux_amd64.zip\",\n" +
            "                                                                        \"shasumsUrl\": \"https://releases.hashicorp.com/terraform-provider-random/3.0.1/terraform-provider-random_3.0.1_SHA256SUMS\",\n" +
            "                                                                        \"shasumsSignatureUrl\": \"https://releases.hashicorp.com/terraform-provider-random/3.0.1/terraform-provider-random_3.0.1_SHA256SUMS.72D7468F.sig\",\n" +
            "                                                                        \"shasum\": \"e385e00e7425dda9d30b74ab4ffa4636f4b8eb23918c0b763f0ffab84ece0c5c\",\n" +
            "                                                                        \"keyId\": \"34365D9472D7468F\",\n" +
            "                                                                        \"asciiArmor\": \"-----BEGIN PGP PUBLIC KEY BLOCK-----\\n\\n-----END PGP PUBLIC KEY BLOCK-----\",\n" +
            "                                                                        \"trustSignature\": \"5.0\",\n" +
            "                                                                        \"source\": \"HashiCorp\",\n" +
            "                                                                        \"sourceUrl\": \"https://www.hashicorp.com/security.html\"\n" +
            "                                                                    }\n" +
            "                                                                }\n" +
            "                                                            ]\n" +
            "                                                        }\n" +
            "                                                    }\n" +
            "                                                }\n" +
            "                                            ]\n" +
            "                                        }\n" +
            "                                    }\n" +
            "                                }\n" +
            "                            ]\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n" +
            "            ]\n" +
            "        }\n" +
            "    }\n" +
            "}";


    @Test
    void providerApiGetTestStep1() {
        wireMockServer.resetAll();
        
        stubFor(post(urlPathEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withBody(PROVIDER_SEARCH_VERSION)));

        when()
                .get("/terraform/providers/v1/simple/random/versions")
                .then()
                .log().all()
                .body("versions[0].version", equalTo("3.0.1"))
                .statusCode(HttpStatus.SC_OK);

    }

    @Test
    void providerApiGetTestStep2() {
        wireMockServer.resetAll();

        stubFor(post(urlPathEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withBody(PROVIDER_SEARCH_IMPLEMENTATION)));

        when()
                .get("/terraform/providers/v1/sampleOrganization/simple/3.0.1/download/linux/amd64")
                .then()
                .log().all()
                .body("protocols",hasSize(1))
                .body("protocols[0]",equalTo("5.0"))
                .body("os",equalTo("linux"))
                .body("arch",equalTo("amd64"))
                .log().all()
                .statusCode(HttpStatus.SC_OK);

    }

    private static final String NO_PROVIDER_VERSIONS = """
            {"data":{"organization":{"edges":[{"node":{"provider":{"edges":[{"node":{"version":{"edges":[]}}}]}}}]}}}
            """;

    private static String deprecatedVersions(String... versions) {
        StringBuilder edges = new StringBuilder();
        for (String version : versions) {
            edges.append(edges.isEmpty() ? "" : ",").append("{\"node\":{\"versionNumber\":\"").append(version).append("\"}}");
        }
        return "{\"data\":{\"organization\":{\"edges\":[{\"node\":{\"provider\":{\"edges\":[{\"node\":{\"version\":{\"edges\":["
                + edges + "]}}}]}}}]}}}";
    }

    @Autowired
    CacheManager cacheManager;

    private void stubVersions(ResponseDefinitionBuilder deprecatedVersionsResponse) {
        wireMockServer.resetAll();
        cacheManager.getCache(CacheConfig.PROVIDER_WARNINGS_CACHE).clear();
        stubFor(post(urlPathEqualTo(GRAPHQL_ENDPOINT))
                .withRequestBody(containing("status!=removed"))
                .willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(PROVIDER_SEARCH_VERSION)));
        stubFor(post(urlPathEqualTo(GRAPHQL_ENDPOINT))
                .withRequestBody(containing("status==deprecated"))
                .willReturn(deprecatedVersionsResponse));
    }

    @Test
    void oneDeprecatedVersionGetsOneWarning() {
        stubVersions(aResponse().withStatus(HttpStatus.SC_OK).withBody(deprecatedVersions("3.0.1")));

        when()
                .get("/terraform/providers/v1/simple/random/versions")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("versions.version", contains("3.0.1"))
                .body("warnings", contains("Version 3.0.1 of simple/random is deprecated. See the private registry for details."));
    }

    // Warnings are per provider and every user sees them, so many deprecated versions still make one short line.
    @Test
    void manyDeprecatedVersionsShareOneCappedWarning() {
        stubVersions(aResponse().withStatus(HttpStatus.SC_OK)
                .withBody(deprecatedVersions("1.0.0", "1.1.0", "1.2.0", "1.3.0", "1.4.0", "1.5.0", "1.6.0")));

        when()
                .get("/terraform/providers/v1/simple/random/versions")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("warnings", contains("Versions 1.0.0, 1.1.0, 1.2.0, 1.3.0, 1.4.0 and 2 more of simple/random are deprecated."
                        + " See the private registry for details."));
    }

    @Test
    void noDeprecatedVersionsMeansNoWarnings() {
        stubVersions(aResponse().withStatus(HttpStatus.SC_OK).withBody(NO_PROVIDER_VERSIONS));

        when()
                .get("/terraform/providers/v1/simple/random/versions")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("$", not(hasKey("warnings")));
    }

    @Test
    void providerVersionsAreServedWhenWarningsCannotBeLoaded() {
        stubVersions(aResponse().withStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR));

        when()
                .get("/terraform/providers/v1/simple/random/versions")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("versions.version", contains("3.0.1"))
                .body("$", not(hasKey("warnings")));
    }

    // The warnings lookup is cached, so terraform's version lookups do not double the API calls.
    @Test
    void warningsAreLoadedOncePerCacheLifetime() {
        stubVersions(aResponse().withStatus(HttpStatus.SC_OK).withBody(deprecatedVersions("3.0.1")));

        when().get("/terraform/providers/v1/simple/random/versions").then().statusCode(HttpStatus.SC_OK);
        when().get("/terraform/providers/v1/simple/random/versions").then().statusCode(HttpStatus.SC_OK);

        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo(GRAPHQL_ENDPOINT)).withRequestBody(containing("status==deprecated")));
    }

    @Test
    void removedProviderVersionIsNotDownloadable() {
        wireMockServer.resetAll();

        stubFor(post(urlPathEqualTo(GRAPHQL_ENDPOINT))
                .withRequestBody(containing("status!=removed"))
                .willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(NO_PROVIDER_VERSIONS)));

        when()
                .get("/terraform/providers/v1/simple/random/2.0.0/download/linux/amd64")
                .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
    }
}
