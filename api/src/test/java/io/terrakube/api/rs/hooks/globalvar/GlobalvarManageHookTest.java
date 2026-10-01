package io.terrakube.api.rs.hooks.globalvar;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.globalvar.Globalvar;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalvarManageHookTest {

    @Mock
    GlobalVarRepository globalVarRepository;

    @Mock
    RequestScope requestScope;

    @InjectMocks
    GlobalvarManageHook hook;

    private Organization organization;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        organization = new Organization();
        organization.setId(orgId);
        organization.setName("test-org");
    }

    @Test
    void create_duplicateKey_throwsConflictException() {
        Globalvar newVar = new Globalvar();
        newVar.setOrganization(organization);
        newVar.setKey("DB_HOST");

        Globalvar existingVar = new Globalvar();
        existingVar.setId(UUID.randomUUID());
        existingVar.setOrganization(organization);
        existingVar.setKey("DB_HOST");

        when(globalVarRepository.findByOrganizationAndKey(organization, "DB_HOST"))
                .thenReturn(Optional.of(existingVar));

        GlobalvarManagementException ex = assertThrows(GlobalvarManagementException.class, () ->
                hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, newVar, requestScope, Optional.empty())
        );

        assertEquals(HttpStatus.SC_CONFLICT, ex.getStatus());
        assertEquals("A global variable with key 'DB_HOST' already exists in this organization.", ex.getMessage());
    }

    @Test
    void create_uniqueKey_succeeds() {
        Globalvar newVar = new Globalvar();
        newVar.setOrganization(organization);
        newVar.setKey("UNIQUE_KEY");

        when(globalVarRepository.findByOrganizationAndKey(organization, "UNIQUE_KEY"))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() ->
                hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, newVar, requestScope, Optional.empty())
        );
    }

    @Test
    void update_sameEntity_succeeds() {
        UUID varId = UUID.randomUUID();
        Globalvar updatingVar = new Globalvar();
        updatingVar.setId(varId);
        updatingVar.setOrganization(organization);
        updatingVar.setKey("DB_HOST");

        Globalvar existingVar = new Globalvar();
        existingVar.setId(varId);
        existingVar.setOrganization(organization);
        existingVar.setKey("DB_HOST");

        when(globalVarRepository.findByOrganizationAndKey(organization, "DB_HOST"))
                .thenReturn(Optional.of(existingVar));

        assertDoesNotThrow(() ->
                hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, updatingVar, requestScope, Optional.empty())
        );
    }

    @Test
    void update_renameToExistingKeyOfAnotherEntity_throwsConflictException() {
        UUID varId = UUID.randomUUID();
        Globalvar updatingVar = new Globalvar();
        updatingVar.setId(varId);
        updatingVar.setOrganization(organization);
        updatingVar.setKey("DB_HOST");

        UUID otherId = UUID.randomUUID();
        Globalvar existingOtherVar = new Globalvar();
        existingOtherVar.setId(otherId);
        existingOtherVar.setOrganization(organization);
        existingOtherVar.setKey("DB_HOST");

        when(globalVarRepository.findByOrganizationAndKey(organization, "DB_HOST"))
                .thenReturn(Optional.of(existingOtherVar));

        GlobalvarManagementException ex = assertThrows(GlobalvarManagementException.class, () ->
                hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, updatingVar, requestScope, Optional.empty())
        );

        assertEquals(HttpStatus.SC_CONFLICT, ex.getStatus());
        assertEquals("A global variable with key 'DB_HOST' already exists in this organization.", ex.getMessage());
    }

    @Test
    void postCommit_doesNotValidate() {
        Globalvar newVar = new Globalvar();
        newVar.setOrganization(organization);
        newVar.setKey("DB_HOST");

        assertDoesNotThrow(() ->
                hook.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, newVar, requestScope, Optional.empty())
        );

        verifyNoInteractions(globalVarRepository);
    }

    @Test
    void annotations_verifyLifeCycleHookBindingsOnGlobalvar() {
        LifeCycleHookBinding[] bindings = Globalvar.class.getAnnotationsByType(LifeCycleHookBinding.class);
        List<LifeCycleHookBinding> hookBindings = Arrays.stream(bindings)
                .filter(b -> b.hook().equals(GlobalvarManageHook.class))
                .toList();

        assertEquals(2, hookBindings.size());
        assertTrue(hookBindings.stream().anyMatch(b ->
                b.operation() == Operation.CREATE && b.phase() == TransactionPhase.PRECOMMIT));
        assertTrue(hookBindings.stream().anyMatch(b ->
                b.operation() == Operation.UPDATE && b.phase() == TransactionPhase.PRECOMMIT));
    }
}
