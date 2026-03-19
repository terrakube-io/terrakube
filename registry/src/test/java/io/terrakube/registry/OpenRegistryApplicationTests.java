package io.terrakube.registry;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.client.WireMock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class OpenRegistryApplicationTests {

	WireMockServer wireMockServer;

	@LocalServerPort
	int port;

	@BeforeAll
	public void setUp() {
		RestAssured.port = port;
		wireMockServer = new WireMockServer(WireMockConfiguration.options().port(9999).bindAddress("localhost"));
		wireMockServer.start();
		WireMock.configureFor("localhost", 9999);
	}

	@AfterAll
	public void stopServer() {
		wireMockServer.stop();
	}

	@Test
	void initLoad() {
	}

}
