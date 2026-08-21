package io.terrakube.api.plugin.notification.sender;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.terrakube.api.plugin.notification.payload.HmacSigner;
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

class WebhookSenderTest {

    private WireMockServer wireMockServer;
    // block-private-destinations off: WireMock runs on localhost, which is a loopback address.
    private final WebhookSender sender = new WebhookSender(RestTemplateFactory.build(), new DestinationUrlValidator(false));

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private NotificationConfiguration configuration(String path, String secret) {
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("http://localhost:" + wireMockServer.port() + path);
        configuration.setSigningSecret(secret);
        return configuration;
    }

    @Test
    void sendsUnsignedPayloadWhenNoSecretConfigured() {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse().withStatus(200)));

        sender.send(configuration("/hook", null), "{\"a\":1}");

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/hook"))
                .withoutHeader("X-Terrakube-Signature"));
    }

    @Test
    void signsPayloadWhenSecretConfigured() {
        String payload = "{\"a\":1}";
        String expectedSignature = HmacSigner.sign("my-secret", payload);
        wireMockServer.stubFor(post(urlPathEqualTo("/hook"))
                .withHeader("X-Terrakube-Signature", equalTo(expectedSignature))
                .willReturn(aResponse().withStatus(200)));

        sender.send(configuration("/hook", "my-secret"), payload);

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/hook")));
    }

    @Test
    void throwsNotificationDeliveryExceptionOnNon2xxResponse() {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> sender.send(configuration("/hook", null), "{}"))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void serverErrorIsClassifiedAsRetryable() {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(503)));

        NotificationDeliveryException e = catchDeliveryException();

        assertThat(e.isRetryable()).isTrue();
    }

    @Test
    void badRequestIsClassifiedAsTerminal() {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(400)));

        NotificationDeliveryException e = catchDeliveryException();

        assertThat(e.isRetryable()).isFalse();
        assertThat(e.getRetryAfter()).isNull();
    }

    @Test
    void rateLimitedParsesHttpDateRetryAfterHeader() {
        ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(90).withNano(0);
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", target.format(DateTimeFormatter.RFC_1123_DATE_TIME))));

        NotificationDeliveryException e = catchDeliveryException();

        assertThat(e.isRetryable()).isTrue();
        assertThat(e.getRetryAfter()).isNotNull();
        assertThat(e.getRetryAfter().toSeconds()).isBetween(60L, 90L);
    }

    private NotificationDeliveryException catchDeliveryException() {
        try {
            sender.send(configuration("/hook", null), "{}");
        } catch (NotificationDeliveryException e) {
            return e;
        }
        throw new AssertionError("expected NotificationDeliveryException to be thrown");
    }
}
