package io.terrakube.api.plugin.subscription;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionWebSocketConfigurationTest {

    @Test
    void lazyDecoderUsesThreadSafeDelegateAndDoesNotResolveDuringConstruction() throws Exception {
        Class<?> decoderClass = Class.forName(
                "io.terrakube.api.plugin.subscription.SubscriptionWebSocketConfiguration$LazyIssuerJwtDecoder");
        Constructor<?> constructor = decoderClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object decoder = constructor.newInstance("https://issuer.example");

        Field delegateField = decoderClass.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(decoder);

        assertThat(delegate).isInstanceOf(AtomicReference.class);
        assertThat(((AtomicReference<?>) delegate).get()).isNull();
    }
}