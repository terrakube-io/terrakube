package io.terrakube.registry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

public class ModuleTests extends OpenRegistryApplicationTests{
    private static final String ORGANIZATION_SEARCH="/api/v1/organization";
    private static final String ORGANIZATION_SEARCH_BODY="{\n" +
            "    \"data\": [\n" +
            "        {\n" +
            "            \"type\": \"organization\",\n" +
            "            \"id\": \"b8938f8f-92c7-40fc-90ce-e4725ee8e985\",\n" +
            "            \"attributes\": {\n" +
            "                \"description\": \"Sample Organization\",\n" +
            "                \"name\": \"moduleOrganization\"\n" +
            "            },\n" +
            "            \"relationships\": {\n" +
            "                \"job\": {\n" +
            "                    \"data\": []\n" +
            "                },\n" +
            "                \"module\": {\n" +
            "                    \"data\": [\n" +
            "                        {\n" +
            "                            \"type\": \"module\",\n" +
            "                            \"id\": \"3f6cd63e-e73a-4a17-b98d-a7f7b6c691a6\"\n" +
            "                        }\n" +
            "                    ]\n" +
            "                },\n" +
            "                \"provider\": {\n" +
            "                    \"data\": []\n" +
            "                },\n" +
            "                \"workspace\": {\n" +
            "                    \"data\": []\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "    ]\n" +
            "}";
    private static final String MODULE_SEARCH="/api/v1/organization/b8938f8f-92c7-40fc-90ce-e4725ee8e985/module";
    private static final String MODULE_SEARCH_BODY="{\n" +
            "    \"data\": [\n" +
            "        {\n" +
            "            \"type\": \"module\",\n" +
            "            \"id\": \"3f6cd63e-e73a-4a17-b98d-a7f7b6c691a6\",\n" +
            "            \"attributes\": {\n" +
            "                \"description\": \"sample Module\",\n" +
            "                \"name\": \"sampleModule\",\n" +
            "                \"provider\": \"sampleProvider\",\n" +
            "                \"registryPath\": \"sampleOrganization/sampleModule/sampleProvider\",\n" +
            "                \"source\": \"https://github.com/AzBuilder/terraform-sample-repository.git\",\n" +
            "                \"sourceSample\": \"https://github.com/AzBuilder/terraform-sample-repository.git\",\n" +
            "                \"latestVersion\": \"0.0.2\"\n" +
            "            },\n" +
            "            \"relationships\": {\n" +
            "                \"organization\": {\n" +
            "                    \"data\": {\n" +
            "                        \"type\": \"organization\",\n" +
            "                        \"id\": \"b8938f8f-92c7-40fc-90ce-e4725ee8e985\"\n" +
            "                      }\n" +
            "                  },\n" +
            "                \"vcs\": {\n" +
            "                    \"data\": null\n" +
            "                   },\n" +
            "                \"version\": {\n" +
            "                    \"data\": [{\n" +
            "                         \"type\": \"module_version\",\n" +
            "                         \"id\": \"223788aa-498b-4f29-82fc-04587a0fcae8\"\n" +
            "                       },\n" +
            "                       {\n" +
            "                          \"type\": \"module_version\",\n" +
            "                          \"id\": \"7f1243ef-cce0-4d6e-8b99-e6c121311b58\"\n" +
            "                       }]\n" +
            "                 }\n" +
            "              }\n" +
            "        }\n" +
            "    ]\n" +
            "}";
    private static final String MODULE_VERSION_PATH = "/api/v1/organization/b8938f8f-92c7-40fc-90ce-e4725ee8e985/module/3f6cd63e-e73a-4a17-b98d-a7f7b6c691a6/version";
    private static final String MODULE_VERSION_BODY = """
                {
                        "data": [{
                                "type": "module_version",
                                "id": "223788aa-498b-4f29-82fc-04587a0fcae8",
                                "attributes": {
                                        "version": "0.0.2",
                                        "commit": "2b2f528116878f440e16ae061efd7572fe02f487"
                                },
                                "relationships": {
                                        "module": {
                                                "data": {
                                                        "type": "module",
                                                        "id": "3f6cd63e-e73a-4a17-b98d-a7f7b6c691a6"
                                                }
                                        }
                                }
                        },
                        {
                                "type": "module_version",
                                "id": "7f1243ef-cce0-4d6e-8b99-e6c121311b58",
                                "attributes": {
                                        "version": "v0.0.1",
                                        "commit": "518e2b32a0846095e2dede7661abc3f71bd44cc4"
                                },
                                "relationships": {
                                        "module": {
                                                "data": {
                                                        "type": "module",
                                                        "id": "3f6cd63e-e73a-4a17-b98d-a7f7b6c691a6"
                                                }
                                        }
                                }
                        }]
                }
        """;

    @Test
    void moduleApiGetTestStep1() {
        wireMockServer.resetAll();
        
        stubFor(get(urlPathEqualTo(ORGANIZATION_SEARCH))
                .withQueryParam("filter[organization]", containing("name==moduleOrganization"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withBody(ORGANIZATION_SEARCH_BODY)));

        stubFor(get(urlPathEqualTo(MODULE_SEARCH))
                .withQueryParam("filter[module]", containing("name==sampleModule;provider==sampleProvider"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withBody(MODULE_SEARCH_BODY)));

        stubFor(get(urlPathEqualTo(MODULE_VERSION_PATH))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withBody(MODULE_VERSION_BODY)));

        when()
                .get("/terraform/modules/v1/moduleOrganization/sampleModule/sampleProvider/versions")
                .then()
                .log().all()
                .body("modules",hasSize(1))
                .body("modules[0].versions",hasSize(2))
                .body("modules[0].versions[0].version",equalTo("0.0.2"))
                .body("modules[0].versions[1].version",equalTo("v0.0.1"))
                .log().all()
                .statusCode(HttpStatus.SC_OK);
    }
}