package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class ExecutorServiceTest {

    @Mock
    private VariableRepository variableRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private Environment environment;

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
    void appliesApiEphemeralDefaultsWithoutOverwritingExistingVariables() {
        when(environment.getProperty("EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP"))
                .thenReturn("terrakube-executor-config");
        when(environment.getProperty("EPHEMERAL_CONFIG_MAP_NAME"))
                .thenReturn("terrakube-ephemeral-ca-certs");
        when(environment.getProperty("EPHEMERAL_CONFIG_MAP_MOUNT_PATH"))
                .thenReturn("/mnt/platform/bindings/ca-certificates");
        when(environment.getProperty("EPHEMERAL_CONFIG_NODE_SELECTOR_TAGS"))
                .thenReturn("caas/poolname=linux");
        when(environment.getProperty("EPHEMERAL_CONFIG_SERVICE_ACCOUNT"))
                .thenReturn("terrakube-api-sa");
        when(environment.getProperty("EPHEMERAL_CONFIG_TOLERATIONS"))
                .thenReturn("caas=true:NoSchedule");
        when(environment.getProperty("EPHEMERAL_CONFIG_ANNOTATIONS"))
                .thenReturn("team=platform");
        when(environment.getProperty("EPHEMERAL_CONFIG_POD_ANNOTATIONS"))
                .thenReturn("vault.hashicorp.com/agent-inject=true");
        when(environment.getProperty("EPHEMERAL_CONFIG_LABELS"))
                .thenReturn("environment=production");
        when(environment.getProperty("EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT"))
                .thenReturn("runAsNonRoot=true");
        when(environment.getProperty("EPHEMERAL_CONFIG_SECURITY_CONTEXT"))
                .thenReturn("allowPrivilegeEscalation=false");
        when(environment.getProperty("EPHEMERAL_CPU_REQUEST"))
                .thenReturn("100m");
        when(environment.getProperty("EPHEMERAL_CPU_LIMIT"))
                .thenReturn("500m");
        when(environment.getProperty("EPHEMERAL_MEMORY_REQUEST"))
                .thenReturn("128Mi");
        when(environment.getProperty("EPHEMERAL_MEMORY_LIMIT"))
                .thenReturn("512Mi");
        when(environment.getProperty("EPHEMERAL_STORAGE_REQUEST"))
                .thenReturn("1Gi");
        when(environment.getProperty("EPHEMERAL_STORAGE_LIMIT"))
                .thenReturn("2Gi");
        when(environment.getProperty("EPHEMERAL_JOB_ENV_VARS"))
                .thenReturn("TERRAKUBE_REDIS_SSL=true");

        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("EPHEMERAL_CONFIG_SERVICE_ACCOUNT", "workspace-service-account");

        executorService.mergeApiEphemeralDefaults(environmentVariables);

        assertThat(environmentVariables)
                .containsEntry("EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP", "terrakube-executor-config")
                .containsEntry("EPHEMERAL_CONFIG_MAP_NAME", "terrakube-ephemeral-ca-certs")
                .containsEntry("EPHEMERAL_CONFIG_MAP_MOUNT_PATH", "/mnt/platform/bindings/ca-certificates")
                .containsEntry("EPHEMERAL_CONFIG_NODE_SELECTOR_TAGS", "caas/poolname=linux")
                .containsEntry("EPHEMERAL_CONFIG_SERVICE_ACCOUNT", "workspace-service-account")
                .containsEntry("EPHEMERAL_CONFIG_TOLERATIONS", "caas=true:NoSchedule")
                .containsEntry("EPHEMERAL_CONFIG_ANNOTATIONS", "team=platform")
                .containsEntry("EPHEMERAL_CONFIG_POD_ANNOTATIONS", "vault.hashicorp.com/agent-inject=true")
                .containsEntry("EPHEMERAL_CONFIG_LABELS", "environment=production")
                .containsEntry("EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT", "runAsNonRoot=true")
                .containsEntry("EPHEMERAL_CONFIG_SECURITY_CONTEXT", "allowPrivilegeEscalation=false")
                .containsEntry("EPHEMERAL_CPU_REQUEST", "100m")
                .containsEntry("EPHEMERAL_CPU_LIMIT", "500m")
                .containsEntry("EPHEMERAL_MEMORY_REQUEST", "128Mi")
                .containsEntry("EPHEMERAL_MEMORY_LIMIT", "512Mi")
                .containsEntry("EPHEMERAL_STORAGE_REQUEST", "1Gi")
                .containsEntry("EPHEMERAL_STORAGE_LIMIT", "2Gi")
                .containsEntry("EPHEMERAL_JOB_ENV_VARS", "TERRAKUBE_REDIS_SSL=true");
    }

    @Test
    void ignoresEmptyApiEphemeralDefaults() {
        when(environment.getProperty("EPHEMERAL_CONFIG_MAP_NAME")).thenReturn(" ");

        HashMap<String, String> environmentVariables = new HashMap<>();
        executorService.mergeApiEphemeralDefaults(environmentVariables);

        assertThat(environmentVariables).isEmpty();
    }

    @Test
    void shouldPersistJobOverrideSourceWhenNotAlreadySet() {
        String resolved = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";

        executorService.persistJobOverrideSource(job, "remote-content", resolved);

        assertThat(job.getOverrideSource()).isEqualTo(resolved);
        verify(jobRepository).save(job);
    }

    @Test
    void shouldNotPersistJobOverrideSourceWhenAlreadySet() {
        String original = "https://localhost/remote/tfe/v2/configuration-versions/original/terraformContent.tar.gz";
        String resolved = "https://localhost/remote/tfe/v2/configuration-versions/abc/terraformContent.tar.gz";
        job.setOverrideSource(original);

        executorService.persistJobOverrideSource(job, "remote-content", resolved);

        assertThat(job.getOverrideSource()).isEqualTo(original);
        verify(jobRepository, never()).save(job);
    }

    @Test
    void shouldNotPersistJobOverrideSourceForNonRemoteContentBranch() {
        String resolved = "https://github.com/example/repo.git";

        executorService.persistJobOverrideSource(job, "main", resolved);

        assertThat(job.getOverrideSource()).isNull();
        verify(jobRepository, never()).save(job);
    }

    @Test
    void shouldNotPersistJobOverrideSourceWhenResolvedSourceIsBlank() {
        executorService.persistJobOverrideSource(job, "remote-content", "  ");

        assertThat(job.getOverrideSource()).isNull();
        verify(jobRepository, never()).save(job);
    }

    @Test
    void shouldNotPersistJobOverrideSourceForVcsWorkspace() {
        // "Run now"'s branch name field is free text - a VCS workspace could arrive here with
        // branch resolved to "remote-content" (e.g. mistyped/pasted), with resolvedSource actually
        // the workspace's git URL via the executorContext.getSource() fallback. Persisting that
        // as job.overrideSource would make the executor try to download a git URL as a tarball.
        workspace.setVcs(new Vcs());
        String gitUrl = "https://github.com/example/repo.git";

        executorService.persistJobOverrideSource(job, "remote-content", gitUrl);

        assertThat(job.getOverrideSource()).isNull();
        verify(jobRepository, never()).save(job);
    }

    @Test
    void shouldNotPersistJobOverrideSourceForSshWorkspace() {
        workspace.setSsh(new Ssh());
        String gitUrl = "git@github.com:example/repo.git";

        executorService.persistJobOverrideSource(job, "remote-content", gitUrl);

        assertThat(job.getOverrideSource()).isNull();
        verify(jobRepository, never()).save(job);
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

    private ExecutorContext createContext() {
        return ExecutorContext.builder()
                .environmentVariables(new HashMap<>())
                .variables(new HashMap<>())
                .build();
    }

    @Test
    void shouldNotSetTfCliArgsWhenAddressListIsEmpty() {
        when(addressRepository.findByJob(job)).thenReturn(List.of());
        ExecutorContext context = createContext();

        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables()).doesNotContainKey("TF_CLI_ARGS_plan");
    }

    @Test
    void shouldSetTfCliArgsPlanForSimpleTargetAddress() {
        Address address = new Address();
        address.setName("aws_s3_bucket.bucket");
        address.setType(AddressType.TARGET);
        when(addressRepository.findByJob(job)).thenReturn(List.of(address));

        ExecutorContext context = createContext();
        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables())
                .containsEntry("TF_CLI_ARGS_plan", " -target=\"aws_s3_bucket.bucket\"");
    }

    @Test
    void shouldEscapeDoubleQuotesInTargetAddressIndexKey() {
        Address address = new Address();
        address.setName("aws_identitystore_user.users[\"name.surname\"]");
        address.setType(AddressType.TARGET);
        when(addressRepository.findByJob(job)).thenReturn(List.of(address));

        ExecutorContext context = createContext();
        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables())
                .containsEntry("TF_CLI_ARGS_plan", " -target=\"aws_identitystore_user.users[\\\"name.surname\\\"]\"");
    }

    @Test
    void shouldEscapeDoubleQuotesInReplaceAddressIndexKey() {
        Address address = new Address();
        address.setName("aws_identitystore_user.users[\"name.surname\"]");
        address.setType(AddressType.REPLACE);
        when(addressRepository.findByJob(job)).thenReturn(List.of(address));

        ExecutorContext context = createContext();
        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables())
                .containsEntry("TF_CLI_ARGS_plan", " -replace=\"aws_identitystore_user.users[\\\"name.surname\\\"]\"");
    }

    @Test
    void shouldHandleMultipleTargetAndReplaceAddressesWithQuotedKeysAndBackslashes() {
        Address target1 = new Address();
        target1.setName("aws_identitystore_user.users[\"name.surname\"]");
        target1.setType(AddressType.TARGET);

        Address target2 = new Address();
        target2.setName("module.user[\"domain\\\\user\"]");
        target2.setType(AddressType.TARGET);

        Address replace = new Address();
        replace.setName("aws_instance.server[\"web-prod\"]");
        replace.setType(AddressType.REPLACE);

        when(addressRepository.findByJob(job)).thenReturn(List.of(target1, target2, replace));

        ExecutorContext context = createContext();
        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables())
                .containsEntry("TF_CLI_ARGS_plan",
                        " -target=\"aws_identitystore_user.users[\\\"name.surname\\\"]\"" +
                        " -target=\"module.user[\\\"domain\\\\\\\\user\\\"]\"" +
                        " -replace=\"aws_instance.server[\\\"web-prod\\\"]\"");
    }

    @Test
    void shouldNotOverwriteExistingTfCliArgsPlan() {
        Address address = new Address();
        address.setName("aws_s3_bucket.bucket");
        address.setType(AddressType.TARGET);
        when(addressRepository.findByJob(job)).thenReturn(List.of(address));

        ExecutorContext context = createContext();
        context.getEnvironmentVariables().put("TF_CLI_ARGS_plan", "-existing-flag");

        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables()).containsEntry("TF_CLI_ARGS_plan", "-existing-flag");
    }
}

