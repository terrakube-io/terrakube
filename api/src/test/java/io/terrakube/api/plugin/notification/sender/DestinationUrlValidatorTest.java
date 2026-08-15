package io.terrakube.api.plugin.notification.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationUrlValidatorTest {

    private final DestinationUrlValidator blocking = new DestinationUrlValidator(true);
    private final DestinationUrlValidator permissive = new DestinationUrlValidator(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/hook",
            "http://localhost/hook",
            "http://10.0.0.5/hook",
            "http://172.16.4.4/hook",
            "http://192.168.1.1/hook",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/hook",
            "http://0.0.0.0/hook",
    })
    void blocksPrivateAndReservedDestinationsByDefault(String url) {
        NotificationDeliveryException e = catchDeliveryException(url);

        assertThat(e.isRetryable()).isFalse();
    }

    private NotificationDeliveryException catchDeliveryException(String url) {
        try {
            blocking.validate("Webhook", url);
        } catch (NotificationDeliveryException e) {
            return e;
        }
        throw new AssertionError("expected NotificationDeliveryException to be thrown for " + url);
    }

    @Test
    void allowsAPublicHostnameByDefault() {
        assertThatCode(() -> blocking.validate("Webhook", "https://hooks.slack.com/services/X/Y/Z"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonHttpSchemesEvenWhenPermissive() {
        assertThatThrownBy(() -> permissive.validate("Webhook", "file:///etc/passwd"))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void rejectsMalformedUrlsEvenWhenPermissive() {
        assertThatThrownBy(() -> permissive.validate("Webhook", "not a url"))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void permissiveModeAllowsLoopbackForLocalTesting() {
        assertThatCode(() -> permissive.validate("Webhook", "http://127.0.0.1:8080/hook"))
                .doesNotThrowAnyException();
    }
}
