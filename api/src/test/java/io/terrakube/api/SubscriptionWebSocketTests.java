package io.terrakube.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SubscriptionWebSocketTests extends ServerApplicationTests {

    @Test
    void applicationContextLoadsWithGraphQlWebSocketConfigured() {
        // If SubscriptionWebSocketConfiguration's bean definition is broken (wrong constructor args,
        // missing property), the whole Spring context fails to start and every test in this class fails -
        // this is a deliberate smoke test for that, not a behavioral test of the interceptor itself.
        assertThat(port).isGreaterThan(0);
    }
}
