package io.terrakube.registry;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.hasSize;

public class ModuleTests extends OpenRegistryApplicationTests{
    private static final String GRAPHQL_ENDPOINT="/graphql/api/v1";
    private static final String ORGANIZATION_SEARCH_BODY="{\n" +
            "    \"data\": {\n" +
            "        \"organization\": {\n" +
            "            \"edges\": [\n" +
            "                {\n" +
            "                    \"node\": {\n" +
            "                        \"id\": \"d9b58bd3-f3fc-4056-a026-1163297e80a8\",\n" +
            "                        \"name\": \"aws\"\n" +
            "                    }\n" +
            "                }\n" +
            "            ]\n" +
            "        }\n" +
            "    }\n" +
            "}";
    private static final String MODULE_SEARCH_BODY="{\n" +
            "    \"data\": {\n" +
            "        \"organization\": {\n" +
            "            \"edges\": [\n" +
            "                {\n" +
            "                    \"node\": {\n" +
            "                        \"id\": \"3a130a1c-d96f-4f99-83b8-58d472567e3a\",\n" +
            "                        \"name\": \"aws\",\n" +
            "                        \"module\": {\n" +
            "                            \"edges\": [\n" +
            "                                {\n" +
            "                                    \"node\": {\n" +
            "                                        \"id\": \"25778e8a-6989-4792-9f38-17bb3f09543b\",\n" +
            "                                        \"name\": \"vpc\",\n" +
            "                                        \"provider\": \"aws\",\n" +
            "                                        \"version\": {\n" +
            "                                            \"edges\": [\n" +
            "                                                {\n" +
            "                                                    \"node\": {\n" +
            "                                                        \"id\": \"4d85165f-0e99-4d45-bdcc-6b6bf4577f27\",\n" +
            "                                                        \"version\": \"v3.3.0\",\n" +
            "                                                        \"commit\": \"67ee4ac34b0f04a92d4ae11011920328fdb830df\"\n" +
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
    void moduleApiGetTestStep1() {
        wireMockServer.resetAll();
        
        stubFor(post(urlPathEqualTo(GRAPHQL_ENDPOINT))
                .withRequestBody(not(containing("module(filter: \"name==vpc;provider==aws\")")))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withBody(ORGANIZATION_SEARCH_BODY)));

        stubFor(post(urlPathEqualTo(GRAPHQL_ENDPOINT))
                .withRequestBody(containing("provider"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withBody(MODULE_SEARCH_BODY)));

        when()
                .get("/terraform/modules/v1/aws/vpc/aws/versions")
                .then()
                .log().all()
                .body("modules",hasSize(1))
                .body("modules[0].versions",hasSize(1))
                .log().all()
                .statusCode(HttpStatus.SC_OK);
    }
}