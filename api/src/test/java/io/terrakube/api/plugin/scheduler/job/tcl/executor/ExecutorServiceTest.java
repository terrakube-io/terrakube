package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.job.Job;
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
}
