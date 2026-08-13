package io.terrakube.api.plugin.notification.sender;

import java.time.Duration;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackSenderTest {

    private WireMockServer wireMockServer;
    // block-private-destinations off: WireMock runs on localhost, which is a loopback address.
    private final SlackSender sender = new SlackSender(RestTemplateFactory.build(), new DestinationUrlValidator(false));

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
        configuration.setChannelType(NotificationChannelType.SLACK);
        configuration.setDestinationUrl("http://localhost:" + wireMockServer.port() + "/services/X/Y/Z");
        return configuration;
    }

    @Test
    void postsBlockKitPayloadToIncomingWebhookUrl() {
        wireMockServer.stubFor(post(urlPathEqualTo("/services/X/Y/Z")).willReturn(aResponse().withStatus(200)));

        sender.send(configuration(), "{\"blocks\":[]}");

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/services/X/Y/Z")));
    }

    @Test
    void throwsNotificationDeliveryExceptionOnNon2xxResponse() {
        wireMockServer.stubFor(post(urlPathEqualTo("/services/X/Y/Z")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> sender.send(configuration(), "{}"))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void aNotFoundIsClassifiedAsTerminal() {
        wireMockServer.stubFor(post(urlPathEqualTo("/services/X/Y/Z")).willReturn(aResponse().withStatus(404)));

        NotificationDeliveryException e = catchDeliveryException();

        assertThat(e.isRetryable()).isFalse();
    }

    @Test
    void rateLimitedIsClassifiedAsRetryableWithRetryAfter() {
        wireMockServer.stubFor(post(urlPathEqualTo("/services/X/Y/Z"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "5")));

        NotificationDeliveryException e = catchDeliveryException();

        assertThat(e.isRetryable()).isTrue();
        assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(5));
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
