package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowConfig;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the executor is told for each step of a job on a workspace that restricts apply to its branch. */
@ExtendWith(MockitoExtension.class)
class RequireCommitOnBranchDispatchTest {

    @Mock
    private VariableRepository variableRepository;
    @Mock
    private GlobalVarRepository globalVarRepository;
    @Mock
    private ReferenceRepository referenceRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private StepRepository stepRepository;
    @Mock
    private PersistentExecutorService persistentExecutorService;
    @Mock
    private EphemeralExecutorService ephemeralExecutorService;
    @InjectMocks
    private ExecutorService executorService;

    private List<JsonNode> dispatch(boolean restricted, String branch) throws Exception {
        FlowConfig config = new Yaml(new Constructor(FlowConfig.class, new LoaderOptions())).load("""
                flow:
                  - type: terraformPlan
                    step: 100
                  - type: terraformApply
                    step: 200
                """);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("test");
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("test");
        workspace.setBranch(branch);
        workspace.setSource("https://example.com/test.git");
        workspace.setTerraformVersion("1.9.0");
        workspace.setRestrictApplyToConfiguredBranch(restricted);
        Job job = new Job();
        job.setId(42);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of()));

        for (var flow : config.getFlow()) {
            executorService.execute(job, String.valueOf(flow.getStep()), flow);
        }

        ArgumentCaptor<ExecutorContext> payload = ArgumentCaptor.forClass(ExecutorContext.class);
        verify(persistentExecutorService, times(2)).send(eq(job), payload.capture());
        // The executor reads the context as JSON.
        return payload.getAllValues().stream().map(context -> (JsonNode) new ObjectMapper().valueToTree(context)).toList();
    }

    @Test
    void onlyTheApplyStepCarriesTheBranchWhenTheWorkspaceRestrictsApply() throws Exception {
        List<JsonNode> steps = dispatch(true, "main");

        assertThat(steps.get(0).get("requireCommitOnBranch").isNull()).isTrue();
        assertThat(steps.get(1).get("requireCommitOnBranch").asText()).isEqualTo("main");
    }

    @Test
    void noStepCarriesItWhenTheWorkspaceDoesNotRestrictApply() throws Exception {
        List<JsonNode> steps = dispatch(false, "main");

        assertThat(steps.get(0).get("requireCommitOnBranch").isNull()).isTrue();
        assertThat(steps.get(1).get("requireCommitOnBranch").isNull()).isTrue();
    }
}
