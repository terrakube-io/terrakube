package io.terrakube.registry;

import io.terrakube.registry.service.module.ModuleDownloadTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** The DEX filter chain: mirror JSON needs a token, archives reach the controller, which needs a ticket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "io.terrakube.registry.authentication.type=DEX",
        "io.terrakube.token.issuer-uri=http://localhost:1/dex",
        "io.terrakube.token.pat=dGVzdC1wYXQtand0LXNlY3JldC1rZXktMzItYnl0ZXMtbG9uZw"
})
@ActiveProfiles("test")
class ProviderMirrorDexSecurityTests {

    private static final String BASE = "/terraform/providers/mirror/v1/simple/registry.terraform.io/hashicorp/random";

    @LocalServerPort
    int port;

    @Autowired
    ModuleDownloadTicketService ticketService;

    private int status(String path) {
        return given().port(port).urlEncodingEnabled(false).get(path).statusCode();
    }

    @Test
    void jsonEndpointsRequireAToken() {
        assertThat(status(BASE + "/index.json")).isEqualTo(401);
        assertThat(status(BASE + "/3.6.0.json")).isEqualTo(401);
    }

    @Test
    void archiveWithoutTicketOrTokenIsRefusedByTheController() {
        assertThat(status(BASE + "/3.6.0/linux_amd64.zip")).isEqualTo(403);
    }

    @Test
    void archiveWithTicketPassesTheChain() {
        String ticket = ticketService.generateMirrorTicket("simple", "registry.terraform.io", "hashicorp", "random", "3.6.0", "linux_amd64");
        // Nothing is stored and pull-through is off, so a valid ticket ends at 404.
        assertThat(status(BASE + "/3.6.0/linux_amd64.zip?ticket=" + ticket)).isEqualTo(404);
    }
}
