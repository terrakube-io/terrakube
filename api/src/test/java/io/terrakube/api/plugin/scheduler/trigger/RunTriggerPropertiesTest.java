package io.terrakube.api.plugin.scheduler.trigger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code max-dependents-per-apply} was renamed to {@code max-outbound-triggers-per-workspace}
 * when the bound it describes moved from dispatch-time truncation to edge-creation-time
 * rejection (#3626). Both names must keep reading and writing the same value, so a deployment
 * that already set the old one doesn't silently get a different limit after upgrading.
 */
class RunTriggerPropertiesTest {

    @Test
    void theDeprecatedNameReadsAndWritesTheSameValueAsTheCurrentOne() {
        RunTriggerProperties properties = new RunTriggerProperties();

        properties.setMaxDependentsPerApply(7);
        assertThat(properties.getMaxOutboundTriggersPerWorkspace()).isEqualTo(7);
        assertThat(properties.getMaxDependentsPerApply()).isEqualTo(7);

        properties.setMaxOutboundTriggersPerWorkspace(12);
        assertThat(properties.getMaxDependentsPerApply()).isEqualTo(12);
    }

    @Test
    void theDefaultIsUnchangedByTheRename() {
        RunTriggerProperties properties = new RunTriggerProperties();
        assertThat(properties.getMaxOutboundTriggersPerWorkspace()).isEqualTo(20);
        assertThat(properties.getMaxDependentsPerApply()).isEqualTo(20);
    }
}
