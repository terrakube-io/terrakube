package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutorServiceTest {

    @Mock
    private VariableRepository variableRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @InjectMocks
    private ExecutorService executorService;

    private Workspace workspace;
    private Job job;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setName("my-workspace");

        job = new Job();
        job.setId(42);
        job.setWorkspace(workspace);
    }

    private Flow flow(FlowType type) {
        Flow flow = new Flow();
        flow.setType(type.name());
        return flow;
    }

    @Test
    void shouldRouteTerraformAndEnvVariablesToTheirRespectiveMaps() throws ExecutionException {
        Variable terraformVariable = new Variable();
        terraformVariable.setKey("instance_type");
        terraformVariable.setValue("t3.micro");
        terraformVariable.setCategory(Category.TERRAFORM);

        Variable envVariable = new Variable();
        envVariable.setKey("AWS_REGION");
        envVariable.setValue("us-east-1");
        envVariable.setCategory(Category.ENV);

        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(terraformVariable, envVariable)));

        HashMap<String, String> terraformVariables = new HashMap<>();
        HashMap<String, String> environmentVariables = new HashMap<>();
        executorService.splitWorkspaceVariablesByCategory(job, terraformVariables, environmentVariables);

        assertThat(terraformVariables).containsEntry("instance_type", "t3.micro");
        assertThat(environmentVariables).containsEntry("AWS_REGION", "us-east-1");
    }

    @Test
    void shouldFailClearlyWithWorkspaceAndKeyWhenCategoryIsNull() {
        Variable malformedVariable = new Variable();
        malformedVariable.setKey("LEGACY_VAR");
        malformedVariable.setValue("super-secret");
        malformedVariable.setCategory(null);

        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(malformedVariable)));

        assertThatThrownBy(() -> executorService.splitWorkspaceVariablesByCategory(job, new HashMap<>(), new HashMap<>()))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("my-workspace")
                .hasMessageContaining("LEGACY_VAR")
                .hasMessageNotContaining("super-secret");
    }

    @Test
    void shouldNotThrowWhenWorkspaceHasNoVariables() throws ExecutionException {
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.empty());

        HashMap<String, String> terraformVariables = new HashMap<>();
        HashMap<String, String> environmentVariables = new HashMap<>();
        executorService.splitWorkspaceVariablesByCategory(job, terraformVariables, environmentVariables);

        assertThat(terraformVariables).isEmpty();
        assertThat(environmentVariables).isEmpty();
    }

    @Test
    void shouldPersistAppliedConfigurationSourceForRemoteContentApply() {
        workspace.setBranch("remote-content");
        workspace.setSource("empty");
        String applied = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";

        executorService.persistAppliedConfigurationSource(job, flow(FlowType.terraformApply), applied);

        assertThat(workspace.getSource()).isEqualTo(applied);
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void shouldNotPersistSourceForSpeculativePlan() {
        workspace.setBranch("remote-content");
        workspace.setSource("empty");
        String planned = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";

        executorService.persistAppliedConfigurationSource(job, flow(FlowType.terraformPlan), planned);

        assertThat(workspace.getSource()).isEqualTo("empty");
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void shouldNotPersistSourceForVcsWorkspaceOnApply() {
        workspace.setBranch("remote-content");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setVcs(new Vcs());
        String applied = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";

        executorService.persistAppliedConfigurationSource(job, flow(FlowType.terraformApply), applied);

        assertThat(workspace.getSource()).isEqualTo("https://github.com/example/repo.git");
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void shouldNotPersistSourceForSshWorkspaceOnApply() {
        workspace.setBranch("remote-content");
        workspace.setSource("git@github.com:example/repo.git");
        workspace.setSsh(new Ssh());
        String applied = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";

        executorService.persistAppliedConfigurationSource(job, flow(FlowType.terraformApply), applied);

        assertThat(workspace.getSource()).isEqualTo("git@github.com:example/repo.git");
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void shouldNotPersistSourceForNonRemoteContentBranchOnApply() {
        workspace.setBranch("main");
        workspace.setSource("https://github.com/example/repo.git");
        String applied = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";

        executorService.persistAppliedConfigurationSource(job, flow(FlowType.terraformApply), applied);

        assertThat(workspace.getSource()).isEqualTo("https://github.com/example/repo.git");
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void shouldNotPersistSourceWhenResolvedSourceMatchesCurrentSource() {
        String applied = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";
        workspace.setBranch("remote-content");
        workspace.setSource(applied);

        executorService.persistAppliedConfigurationSource(job, flow(FlowType.terraformApply), applied);

        assertThat(workspace.getSource()).isEqualTo(applied);
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void shouldNotPersistSourceWhenResolvedSourceIsBlankOnApply() {
        workspace.setBranch("remote-content");
        workspace.setSource("empty");

        executorService.persistAppliedConfigurationSource(job, flow(FlowType.terraformApply), "  ");

        assertThat(workspace.getSource()).isEqualTo("empty");
        verify(workspaceRepository, never()).save(workspace);
    }
}
