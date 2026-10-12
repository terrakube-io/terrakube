package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AgentUrlValidatorTest {

    private final AgentUrlValidator permissiveValidator = new AgentUrlValidator(false);
    private final AgentUrlValidator strictValidator = new AgentUrlValidator(true);

    @Test
    void testPermissiveAllowsStandardInternalClusterUrls() {
        // Internal cluster hostnames and IPs are allowed by default for Kubernetes/Docker Compose setups
        assertDoesNotThrow(() -> permissiveValidator.validate("agent", "http://default-executor:8080/api/v1/terraform-rs"));
        assertDoesNotThrow(() -> permissiveValidator.validate("agent", "https://agent.corp.internal/api/v1/terraform-rs"));
        assertDoesNotThrow(() -> permissiveValidator.validate("agent", "http://127.0.0.1:8080/api/v1/terraform-rs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data",
            "http://169.254.169.254:8080/api/v1",
            "https://169.254.1.1/creds"
    })
    void testAlwaysBlocksCloudMetadataAddresses(String url) {
        // Both permissive and strict MUST block link-local/cloud metadata
        assertThrows(IllegalArgumentException.class, () -> permissiveValidator.validate("agent", url));
        assertThrows(IllegalArgumentException.class, () -> strictValidator.validate("agent", url));
    }

    @Test
    void testAlwaysBlocksEmbeddedUserCredentials() {
        assertThrows(IllegalArgumentException.class, () ->
                permissiveValidator.validate("agent", "http://user:password@executor.internal:8080/api/v1")
        );
        assertThrows(IllegalArgumentException.class, () ->
                strictValidator.validate("agent", "http://admin:secret@10.0.0.1:8080/api/v1")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://evil.com/1",
            "ftp://files.internal/archive",
            "javascript:alert(1)"
    })
    void testAlwaysBlocksNonHttpSchemes(String url) {
        assertThrows(IllegalArgumentException.class, () -> permissiveValidator.validate("agent", url));
        assertThrows(IllegalArgumentException.class, () -> strictValidator.validate("agent", url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/api/v1",
            "http://localhost:8080/api/v1",
            "http://10.0.0.5:8080/api/v1",
            "http://172.16.0.10:8080/api/v1",
            "http://192.168.1.100:8080/api/v1"
    })
    void testStrictBlocksPrivateAndLoopbackAddresses(String url) {
        assertThrows(IllegalArgumentException.class, () -> strictValidator.validate("agent", url));
    }

    @Test
    void testNullOrBlankUrlThrows() {
        assertThrows(IllegalArgumentException.class, () -> permissiveValidator.validate("agent", null));
        assertThrows(IllegalArgumentException.class, () -> permissiveValidator.validate("agent", "   "));
        assertThrows(IllegalArgumentException.class, () -> permissiveValidator.validate("agent", "not a uri"));
    }

    @Test
    void testSpringBeanInstantiationHonorsProperty() {
        org.springframework.context.annotation.AnnotationConfigApplicationContext context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test", java.util.Map.of("io.terrakube.agent.ssrf.blockPrivateNetworks", "false"))
        );
        context.register(AgentUrlValidator.class);
        context.refresh();
        AgentUrlValidator validator = context.getBean(AgentUrlValidator.class);
        assertDoesNotThrow(() -> validator.validate("agent", "http://127.0.0.1:8080/api/v1"));
        context.close();
    }

    @Test
    void testSpringBeanInstantiationWithDemoProfile() {
        org.springframework.boot.builder.SpringApplicationBuilder builder = new org.springframework.boot.builder.SpringApplicationBuilder(AgentUrlValidator.class)
                .profiles("demo")
                .web(org.springframework.boot.WebApplicationType.NONE);
        org.springframework.context.ConfigurableApplicationContext context = builder.run();
        AgentUrlValidator validator = context.getBean(AgentUrlValidator.class);
        assertDoesNotThrow(() -> validator.validate("agent", "http://127.0.0.1:8080/api/v1"));
        context.close();
    }

    @Test
    void testSpringBeanInstantiationDefaultsToBlockingPrivateNetworks() {
        org.springframework.boot.builder.SpringApplicationBuilder builder = new org.springframework.boot.builder.SpringApplicationBuilder(AgentUrlValidator.class)
                .web(org.springframework.boot.WebApplicationType.NONE);
        org.springframework.context.ConfigurableApplicationContext context = builder.run();
        AgentUrlValidator validator = context.getBean(AgentUrlValidator.class);
        assertThrows(IllegalArgumentException.class, () -> validator.validate("agent", "http://127.0.0.1:8080/api/v1"));
        context.close();
    }
}

