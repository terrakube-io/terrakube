package io.terrakube.api.rs.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.terrakube.api.rs.Organization;

class PrApplyRequirementTest {

    @Test
    void approvedIsStricterWhicheverSideItIsOn() {
        assertThat(PrApplyRequirement.strictest(PrApplyRequirement.WRITE, PrApplyRequirement.APPROVED))
                .isEqualTo(PrApplyRequirement.APPROVED);
        assertThat(PrApplyRequirement.strictest(PrApplyRequirement.APPROVED, PrApplyRequirement.WRITE))
                .isEqualTo(PrApplyRequirement.APPROVED);
        assertThat(PrApplyRequirement.strictest(null, null)).isEqualTo(PrApplyRequirement.WRITE);
    }

    @Test
    void aNullTriggerRequirementIsStoredAsWrite() {
        WebhookEvent event = new WebhookEvent();
        event.setPrApplyRequirement(PrApplyRequirement.APPROVED);

        event.setPrApplyRequirement(null);

        assertThat(event.getPrApplyRequirement()).isEqualTo(PrApplyRequirement.WRITE);
    }

    @Test
    void aNullOrganizationMinimumIsStoredAsWrite() {
        Organization organization = new Organization();
        organization.setPrApplyMinRequirement(PrApplyRequirement.APPROVED);

        organization.setPrApplyMinRequirement(null);

        assertThat(organization.getPrApplyMinRequirement()).isEqualTo(PrApplyRequirement.WRITE);
    }
}
