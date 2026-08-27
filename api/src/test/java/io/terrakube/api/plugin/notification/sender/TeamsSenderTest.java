package io.terrakube.api.plugin.notification.sender;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.terrakube.api.plugin.proxy.RestTemplateFactory;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class TeamsSenderTest {

    private WireMockServer wireMockServer;
    // block-private-destinations off: WireMock runs on localhost, which is a loopback address.
    private final TeamsSender sender = new TeamsSender(RestTemplateFactory.build(), new DestinationUrlValidator(false));

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private NotificationConfiguration configuration() {
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setChannelType(NotificationChannelType.TEAMS);
        configuration.setDestinationUrl("http://localhost:" + wireMockServer.port() + "/webhookb2/X");
        return configuration;
    }

    @Test
    void postsAdaptiveCardPayloadToIncomingWebhookUrl() {
        wireMockServer.stubFor(post(urlPathEqualTo("/webhookb2/X")).willReturn(aResponse().withStatus(200)));

        sender.send(configuration(), "{\"type\":\"AdaptiveCard\"}");

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/webhookb2/X")));
    }

    @Test
    void notFoundIsClassifiedAsTerminal() {
        wireMockServer.stubFor(post(urlPathEqualTo("/webhookb2/X")).willReturn(aResponse().withStatus(404)));

        NotificationDeliveryException e = catchDeliveryException();

        assertThat(e.isRetryable()).isFalse();
    }

    @Test
    void serverErrorIsClassifiedAsRetryable() {
        wireMockServer.stubFor(post(urlPathEqualTo("/webhookb2/X")).willReturn(aResponse().withStatus(502)));

        NotificationDeliveryException e = catchDeliveryException();

        assertThat(e.isRetryable()).isTrue();
    }

    private NotificationDeliveryException catchDeliveryException() {
        try {
            sender.send(configuration(), "{}");
        } catch (NotificationDeliveryException e) {
            return e;
        }
        throw new AssertionError("expected NotificationDeliveryException to be thrown");
    }
}
