package io.terrakube.registry;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

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
}