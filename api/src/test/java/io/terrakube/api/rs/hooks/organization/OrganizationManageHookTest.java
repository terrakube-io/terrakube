package io.terrakube.api.rs.hooks.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.Test;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;

import io.terrakube.api.plugin.manage.OrganizationManageService;
import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.rs.Organization;

class OrganizationManageHookTest {

    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final SoftDeleteService softDeleteService = mock(SoftDeleteService.class);
    private final OrganizationManageHook hook = new OrganizationManageHook(
            mock(OrganizationManageService.class), softDeleteService, organizationRepository);

    private static Organization organization(String name) {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName(name);
        return organization;
    }

    @Test
    void createRejectsNameThatDiffersOnlyByCase() {
        Organization existing = organization("acme");
        Organization created = organization("Acme");
        when(organizationRepository.findAllByNameIgnoreCase("Acme")).thenReturn(List.of(existing, created));

        assertThatThrownBy(() -> hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, created, null,
                Optional.empty()))
                .isInstanceOfSatisfying(OrganizationManagementException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT));
    }

    @Test
    void createAllowsNameWhenOnlyTheNewOrganizationMatches() {
        Organization created = organization("Acme");
        when(organizationRepository.findAllByNameIgnoreCase("Acme")).thenReturn(List.of(created));

        assertThatCode(() -> hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, created, null,
                Optional.empty())).doesNotThrowAnyException();
    }

    @Test
    void renameRejectsNameThatDiffersOnlyByCase() {
        Organization existing = organization("acme");
        Organization renamed = organization("ACME");
        when(organizationRepository.findAllByNameIgnoreCase("ACME")).thenReturn(List.of(existing, renamed));

        assertThatThrownBy(() -> hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, renamed, null,
                Optional.of(new ChangeSpec(null, "name", "other", "ACME"))))
                .isInstanceOfSatisfying(OrganizationManagementException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT));
    }

    @Test
    void renameAllowsChangingTheCaseOfTheSameOrganization() {
        Organization renamed = organization("ACME");
        when(organizationRepository.findAllByNameIgnoreCase("ACME")).thenReturn(List.of(renamed));

        assertThatCode(() -> hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, renamed, null,
                Optional.of(new ChangeSpec(null, "name", "acme", "ACME")))).doesNotThrowAnyException();
    }

    @Test
    void otherFieldUpdatesDoNotCheckTheName() {
        Organization organization = organization("Acme");

        hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, organization, null,
                Optional.of(new ChangeSpec(null, "description", "old", "new")));

        verify(organizationRepository, never()).findAllByNameIgnoreCase(anyString());
    }

    @Test
    void disablingStillDisablesTheOrganization() {
        Organization organization = organization("Acme");
        organization.setDisabled(true);

        hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, organization, null,
                Optional.of(new ChangeSpec(null, "disabled", false, true)));

        verify(softDeleteService).disableOrganization(organization);
        verify(organizationRepository, never()).findAllByNameIgnoreCase(anyString());
    }

    @Test
    void updateWithoutChangeSpecDoesNothing() {
        Organization organization = organization("Acme");
        organization.setDisabled(true);

        hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, organization, null, Optional.empty());

        verify(organizationRepository, never()).findAllByNameIgnoreCase(anyString());
        verify(softDeleteService, never()).disableOrganization(organization);
    }
}
