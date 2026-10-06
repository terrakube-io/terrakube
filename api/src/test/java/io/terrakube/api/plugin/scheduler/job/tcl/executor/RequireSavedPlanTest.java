package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowConfig;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequireSavedPlanTest {

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

    @ParameterizedTest
    @CsvSource({"omitted, false", "false, false", "true, false", "omitted, true", "false, true", "true, true"})
    void dispatchesTheSettingFromEachTemplateStep(String setting, boolean ephemeral) throws Exception {
        String template = """
                flow:
                  - type: terraformPlan
                    step: 100
                  - type: terraformApply
                    step: 200
                """;
        if (!"omitted".equals(setting)) {
            template += "    requireSavedPlan: " + setting + "\n";
        }
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
        Variable executorMode = new Variable();
        executorMode.setCategory(Category.ENV);
        executorMode.setKey("TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR");
        executorMode.setValue(ephemeral ? "1" : "0");
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(executorMode)));

        for (var flow : config.getFlow()) {
            executorService.execute(job, String.valueOf(flow.getStep()), flow);
        }

        ArgumentCaptor<ExecutorContext> payload = ArgumentCaptor.forClass(ExecutorContext.class);
        if (ephemeral) {
            verify(ephemeralExecutorService, times(2)).send(eq(job), payload.capture());
            verifyNoInteractions(persistentExecutorService);
        } else {
            verify(persistentExecutorService, times(2)).send(eq(job), payload.capture());
            verifyNoInteractions(ephemeralExecutorService);
        }
        assertThat(payload.getAllValues().get(0).isRequireSavedPlan()).isFalse();
        var applyPayload = new ObjectMapper().valueToTree(payload.getAllValues().get(1));
        assertThat(applyPayload.get("requireSavedPlan").asBoolean()).isEqualTo("true".equals(setting));
    }
}
