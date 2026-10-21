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
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What reaches the executor for plan artifacts: the field names must match the executor's
 * TerraformJob, which is deserialised from this payload by name.
 */
@ExtendWith(MockitoExtension.class)
class PlanArtifactsContextTest {

    private static final String TEMPLATE = """
            flow:
              - type: terraformPlan
                step: 100
                artifacts:
                  - "build/*-report.zip"
              - type: terraformApply
                step: 200
            """;

    @Mock
    private StepRepository stepRepository;
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
    @InjectMocks
    private ExecutorService executorService;

    private JsonNode planPayload(String template, int steps, String artifactsVariable, boolean ephemeral) throws Exception {
        FlowConfig config = new Yaml(new Constructor(FlowConfig.class, new LoaderOptions())).load(template);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("test");
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("test");
        workspace.setBranch("main");
        workspace.setSource("https://example.com/test.git");
        workspace.setTerraformVersion("1.9.0");
        Job job = new Job();
        job.setId(42);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        // Job.step is lazy and the scheduler calls execute outside a session, so the count has to come from the repository.
        when(stepRepository.countByJobId(42)).thenReturn((long) steps);

        List<Variable> variables = new ArrayList<>();
        Variable executorMode = new Variable();
        executorMode.setCategory(Category.ENV);
        executorMode.setKey("TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR");
        executorMode.setValue(ephemeral ? "1" : "0");
        variables.add(executorMode);
        if (artifactsVariable != null) {
            Variable artifacts = new Variable();
            artifacts.setCategory(Category.ENV);
            artifacts.setKey("TERRAKUBE_PLAN_ARTIFACTS");
            artifacts.setValue(artifactsVariable);
            variables.add(artifacts);
        }
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(variables));

        executorService.execute(job, "100", config.getFlow().get(0));

        ArgumentCaptor<ExecutorContext> payload = ArgumentCaptor.forClass(ExecutorContext.class);
        if (ephemeral) {
            verify(ephemeralExecutorService).send(eq(job), payload.capture());
        } else {
            verify(persistentExecutorService).send(eq(job), payload.capture());
        }
        return new ObjectMapper().valueToTree(payload.getValue());
    }

    @Test
    void theTemplatesArtifactsAndWhetherAnotherStepFollowsReachThePersistentExecutor() throws Exception {
        JsonNode payload = planPayload(TEMPLATE, 2, null, false);

        assertThat(payload.get("artifactPatterns")).hasSize(1);
        assertThat(payload.get("artifactPatterns").get(0).asText()).isEqualTo("build/*-report.zip");
        assertThat(payload.get("multiStepJob").asBoolean()).isTrue();
    }

    @Test
    void theSamePayloadReachesTheEphemeralExecutor() throws Exception {
        JsonNode payload = planPayload(TEMPLATE, 2, null, true);

        assertThat(payload.get("artifactPatterns").get(0).asText()).isEqualTo("build/*-report.zip");
        assertThat(payload.get("multiStepJob").asBoolean()).isTrue();
    }

    @Test
    void aSingleStepJobIsNotMultiStep() throws Exception {
        assertThat(planPayload(TEMPLATE, 1, null, false).get("multiStepJob").asBoolean()).isFalse();
    }

    @Test
    void theWorkspaceVariableReplacesTheTemplateList() throws Exception {
        JsonNode payload = planPayload(TEMPLATE, 2, "dist/*.zip\noptional:layers/**", false);

        assertThat(payload.get("artifactPatterns")).hasSize(1);
        assertThat(payload.get("artifactPatterns").get(0).asText()).isEqualTo("dist/*.zip\noptional:layers/**");
    }

    @Test
    void anEmptyWorkspaceVariableSwitchesThemOff() throws Exception {
        assertThat(planPayload(TEMPLATE, 2, "", false).get("artifactPatterns")).isEmpty();
    }

    @Test
    void aTemplateWithoutArtifactsSendsNone() throws Exception {
        JsonNode payload = planPayload("flow:\n  - type: terraformPlan\n    step: 100\n", 2, null, false);

        assertThat(payload.get("artifactPatterns") == null || payload.get("artifactPatterns").isNull()).isTrue();
    }
}
