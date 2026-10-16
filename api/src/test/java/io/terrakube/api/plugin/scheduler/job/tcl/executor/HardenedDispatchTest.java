package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import io.terrakube.api.plugin.hardened.RunnerTokenService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HardenedDispatchTest {

    @Mock
    private VariableRepository variableRepository;
    @Mock
    private GlobalVarRepository globalVarRepository;
    @Mock
    private ReferenceRepository referenceRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private PersistentExecutorService persistentExecutorService;
    @Mock
    private EphemeralExecutorService ephemeralExecutorService;
    @Mock
    private RunnerTokenService runnerTokenService;
    @Mock
    private io.terrakube.api.plugin.hardened.RunnerSourceService runnerSourceService;
    @InjectMocks
    private ExecutorService executorService;

    @Test
    void hardenedModeForcesEphemeralRunnersWithAScopedToken() throws Exception {
        Job job = job();
        Variable optOut = new Variable();
        optOut.setCategory(Category.ENV);
        optOut.setKey("TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR");
        optOut.setValue("0");
        when(variableRepository.findByWorkspace(job.getWorkspace())).thenReturn(Optional.of(List.of(optOut)));
        when(runnerTokenService.isEnabled()).thenReturn(true);
        when(runnerTokenService.mint(job.getOrganization().getId().toString(), job.getWorkspace().getId().toString(), "42", "100", "terraformPlan"))
                .thenReturn("runner.jwt.token");

        executorService.execute(job, "100", flow());

        ArgumentCaptor<ExecutorContext> payload = ArgumentCaptor.forClass(ExecutorContext.class);
        verify(ephemeralExecutorService).send(eq(job), payload.capture());
        verify(runnerSourceService).prepare(eq(job), any(ExecutorContext.class));
        verifyNoInteractions(persistentExecutorService);
        assertThat(payload.getValue().getRunnerToken()).isEqualTo("runner.jwt.token");
        assertThat(payload.getValue().getEnvironmentVariables()).doesNotContainValue("runner.jwt.token");
        assertThat(payload.getValue().toString()).doesNotContain("runner.jwt.token");
    }

    @Test
    void legacyModeIsUnchanged() throws Exception {
        Job job = job();
        when(variableRepository.findByWorkspace(job.getWorkspace())).thenReturn(Optional.empty());
        when(runnerTokenService.isEnabled()).thenReturn(false);

        executorService.execute(job, "100", flow());

        ArgumentCaptor<ExecutorContext> payload = ArgumentCaptor.forClass(ExecutorContext.class);
        verify(persistentExecutorService).send(eq(job), payload.capture());
        verifyNoInteractions(ephemeralExecutorService, runnerSourceService);
        assertThat(payload.getValue().getRunnerToken()).isNull();
    }

    @Test
    void persistentExecutorRefusesHardenedJobs() {
        PersistentExecutorService persistent = new PersistentExecutorService(null, null, null, null);
        ExecutorContext context = ExecutorContext.builder().runnerToken("runner.jwt.token").build();
        Job job = job();
        assertThatThrownBy(() -> persistent.send(job, context)).isInstanceOf(ExecutionException.class);
    }

    private Job job() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("org");
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("ws");
        workspace.setBranch("main");
        workspace.setSource("https://example.com/repo.git");
        workspace.setTerraformVersion("1.9.0");
        workspace.setAgent(new Agent());
        Job job = new Job();
        job.setId(42);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        return job;
    }

    private Flow flow() {
        Flow flow = new Flow();
        flow.setType("terraformPlan");
        flow.setStep(100);
        return flow;
    }
}
