package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.plugin.vcs.provider.exception.VcsTokenAcquisitionException;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.ArgumentCaptor;
import io.terrakube.api.plugin.execution.JobVariablesService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorService;
import io.terrakube.api.plugin.token.dynamic.DynamicCredentialsService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.StepRepository;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private TokenService tokenService;

    @Mock private GlobalVarRepository globalVarRepository;
    @Mock private ReferenceRepository referenceRepository;
    @Mock private StepRepository stepRepository;
    @Mock private PersistentExecutorService persistentExecutorService;
    @Mock private DynamicCredentialsService dynamicCredentialsService;
    @Spy private JobVariablesService jobVariablesService = new JobVariablesService(encryption());

    private static io.terrakube.api.plugin.security.encryption.EncryptionService encryption() {
        var encryption = new io.terrakube.api.plugin.security.encryption.EncryptionService();
        org.springframework.test.util.ReflectionTestUtils.setField(encryption, "internalToken", "test-key-for-job-variable-encryption");
        return encryption;
    }

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
    void shouldUsePersistentExecutorWhenEphemeralExecutorFlagIsMissing() {
        assertThat(ExecutorService.isEphemeralExecutorEnabled(Map.of())).isFalse();
    }

    @Test
    void shouldUseEphemeralExecutorWhenEphemeralExecutorFlagIsEnabled() {
        assertThat(ExecutorService.isEphemeralExecutorEnabled(
                Map.of("TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR", "1"))).isTrue();
    }

    @Test
    void shouldUsePersistentExecutorWhenEphemeralExecutorFlagIsDisabled() {
        assertThat(ExecutorService.isEphemeralExecutorEnabled(
                Map.of("TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR", "0"))).isFalse();
    }

    @Test
    void planArtifactsComeFromTheTemplateWhenTheWorkspaceDoesNotSetThem() {
        assertThat(ExecutorService.resolveArtifactPatterns(List.of("build/**"), Map.of("AWS_REGION", "eu-west-1")))
                .containsExactly("build/**");
        assertThat(ExecutorService.resolveArtifactPatterns(null, Map.of())).isNull();
    }

    @Test
    void aWorkspaceVariableReplacesTheTemplateArtifacts() {
        assertThat(ExecutorService.resolveArtifactPatterns(List.of("build/**"),
                Map.of("TERRAKUBE_PLAN_ARTIFACTS", "dist/*.zip\nlayers/**")))
                .containsExactly("dist/*.zip\nlayers/**");
    }

    @Test
    void aWorkspaceVariableWorksWithoutAnyTemplateArtifacts() {
        assertThat(ExecutorService.resolveArtifactPatterns(null, Map.of("TERRAKUBE_PLAN_ARTIFACTS", "build/**")))
                .containsExactly("build/**");
    }

    @Test
    void anEmptyWorkspaceVariableSwitchesTheTemplateArtifactsOff() {
        assertThat(ExecutorService.resolveArtifactPatterns(List.of("build/**"), Map.of("TERRAKUBE_PLAN_ARTIFACTS", "  ")))
                .isEmpty();
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
    void aJobWithoutAResolvedVersionPassesTheWorkspaceVersionThrough() {
        workspace.setTerraformVersion(">= 1.3.0");

        assertThat(ExecutorService.terraformVersionFor(job)).isEqualTo(">= 1.3.0");
    }

    @Test
    void aJobKeepsItsResolvedVersionEvenIfTheWorkspaceConstraintCouldNowResolveToSomethingNewer() {
        workspace.setTerraformVersion(">= 1.3.0");
        job.setResolvedTerraformVersion("1.16.5");

        // The plan step resolved 1.16.5; whatever the release index now says, the apply step must use it too.
        assertThat(ExecutorService.terraformVersionFor(job)).isEqualTo("1.16.5");
    }

    @Test
    void aNewJobOnTheSameWorkspaceStillResolvesAFreshVersion() {
        workspace.setTerraformVersion(">= 1.3.0");
        job.setResolvedTerraformVersion("1.16.5");
        Job next = new Job();
        next.setId(43);
        next.setWorkspace(workspace);

        assertThat(ExecutorService.terraformVersionFor(next)).isEqualTo(">= 1.3.0");
    }

    @Test
    void aBlankResolvedVersionIsIgnored() {
        workspace.setTerraformVersion("1.5.7");
        job.setResolvedTerraformVersion(" ");

        assertThat(ExecutorService.terraformVersionFor(job)).isEqualTo("1.5.7");
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
    void shouldNotPersistJobOverrideSourceWhenResolvedSourceIsEmptyLiteral() {
        executorService.persistJobOverrideSource(job, "remote-content", "empty");

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
    void shouldAppendAddressesToExistingTfCliArgsPlan() {
        Address address = new Address();
        address.setName("aws_s3_bucket.bucket");
        address.setType(AddressType.TARGET);
        when(addressRepository.findByJob(job)).thenReturn(List.of(address));

        ExecutorContext context = createContext();
        context.getEnvironmentVariables().put("TF_CLI_ARGS_plan", "-var-file=variables/dev.tfvars");

        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables())
                .containsEntry("TF_CLI_ARGS_plan", "-var-file=variables/dev.tfvars -target=\"aws_s3_bucket.bucket\"");
    }

    @Test
    void shouldAppendTargetAndReplaceAddressesToExistingTfCliArgsPlan() {
        Address target = new Address();
        target.setName("aws_s3_bucket.bucket");
        target.setType(AddressType.TARGET);

        Address replace = new Address();
        replace.setName("aws_instance.server[\"web-prod\"]");
        replace.setType(AddressType.REPLACE);

        when(addressRepository.findByJob(job)).thenReturn(List.of(target, replace));

        ExecutorContext context = createContext();
        context.getEnvironmentVariables().put("TF_CLI_ARGS_plan", "-var-file=variables/dev.tfvars");

        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables())
                .containsEntry("TF_CLI_ARGS_plan",
                        "-var-file=variables/dev.tfvars" +
                        " -target=\"aws_s3_bucket.bucket\"" +
                        " -replace=\"aws_instance.server[\\\"web-prod\\\"]\"");
    }

    @Test
    void shouldAppendAddressesToEmptyExistingTfCliArgsPlan() {
        Address address = new Address();
        address.setName("aws_s3_bucket.bucket");
        address.setType(AddressType.TARGET);
        when(addressRepository.findByJob(job)).thenReturn(List.of(address));

        ExecutorContext context = createContext();
        context.getEnvironmentVariables().put("TF_CLI_ARGS_plan", "");

        ExecutorContext result = executorService.validateJobAddress(context, job);

        assertThat(result.getEnvironmentVariables())
                .containsEntry("TF_CLI_ARGS_plan", " -target=\"aws_s3_bucket.bucket\"");
    }

    private Job jobWithVcsWorkspace() {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setConnectionType(VcsConnectionType.STANDALONE);

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        Workspace vcsWorkspace = new Workspace();
        vcsWorkspace.setId(UUID.randomUUID());
        vcsWorkspace.setName("my-workspace");
        vcsWorkspace.setBranch("main");
        vcsWorkspace.setVcs(vcs);
        vcsWorkspace.setSource("https://github.com/example/repo.git");

        Job vcsJob = new Job();
        vcsJob.setId(42);
        vcsJob.setOrganization(organization);
        vcsJob.setWorkspace(vcsWorkspace);
        return vcsJob;
    }

    // Issue #3665: a terminal VcsTokenAcquisitionException (e.g. the GitHub App is not
    // installed) must stop dispatch with a plain terminal ExecutionException, not escape
    // unchecked or be swallowed.
    @Test
    void shouldRethrowTerminalVcsTokenFailureAsExecutionException() throws Exception {
        Job vcsJob = jobWithVcsWorkspace();
        when(tokenService.getAccessToken(vcsJob.getWorkspace().getSource(), vcsJob.getWorkspace().getVcs()))
                .thenThrow(new VcsTokenAcquisitionException("GitHub App cannot access 'example/repo' (404).", null, false));

        assertThatThrownBy(() -> executorService.execute(vcsJob, "step-1", flow(FlowType.terraformPlan)))
                .isInstanceOf(ExecutionException.class)
                .isNotInstanceOf(DispatchRetryableException.class)
                .hasMessageContaining("GitHub App cannot access");
    }

    // Issue #3665/#3666: a retryable VcsTokenAcquisitionException (e.g. a GitHub rate limit)
    // must surface as DispatchRetryableException, carrying the provider's retry-after hint, so
    // ScheduleJob's bounded dispatch-retry budget (not an instant failure) handles it.
    @Test
    void shouldRethrowRetryableVcsTokenFailureAsDispatchRetryableExceptionWithHint() throws Exception {
        Job vcsJob = jobWithVcsWorkspace();
        Duration retryAfter = Duration.ofSeconds(30);
        when(tokenService.getAccessToken(vcsJob.getWorkspace().getSource(), vcsJob.getWorkspace().getVcs()))
                .thenThrow(new VcsTokenAcquisitionException("GitHub rate-limited this request (429). Will retry.",
                        null, true, retryAfter));

        assertThatThrownBy(() -> executorService.execute(vcsJob, "step-1", flow(FlowType.terraformPlan)))
                .isInstanceOf(DispatchRetryableException.class)
                .extracting(thrown -> ((DispatchRetryableException) thrown).getRetryAfter())
                .isEqualTo(retryAfter);
    }
    @Test
    void jobVariablesOverrideWorkspaceInputsAndReachPlanAndSavedApplyWithoutMutation() throws Exception {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("test");
        job.setOrganization(organization);
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setBranch("main");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setTerraformVersion("1.14.0");
        workspace.setIacType("terraform");
        Variable old = new Variable();
        old.setKey("key");
        old.setValue("workspace-default");
        old.setCategory(Category.TERRAFORM);
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(old)));
        job.setEncryptedVariables(jobVariablesService.encrypt(
                Map.of("key", "private-key-first\nprivate-key-second\n", "empty", "")));
        executorService.execute(job, "plan-step", flow(FlowType.terraformPlan));
        job.setTerraformPlan("saved-plan");
        Flow apply = flow(FlowType.terraformApply);
        apply.setRequireSavedPlan(true);
        executorService.execute(job, "apply-step", apply);
        var contexts = ArgumentCaptor.forClass(ExecutorContext.class);
        verify(persistentExecutorService, org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.eq(job), contexts.capture());
        for (ExecutorContext context : contexts.getAllValues()) {
            assertThat(context.getEnvironmentVariables()).containsEntry("TF_VAR_key", "private-key-first\nprivate-key-second\n");
            assertThat(context.getEnvironmentVariables()).containsEntry("TF_VAR_empty", "");
            assertThat(context.getVariables()).doesNotContainKey("key");
            assertThat(context.toString()).doesNotContain("private-key");
        }
        assertThat(contexts.getValue().isRequireSavedPlan()).isTrue();
        assertThat(old.getValue()).isEqualTo("workspace-default");
        verify(variableRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cliRunVariablesReplaceWorkspaceInputsAsArgumentsForPlanAndSavedApply() throws Exception {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("test");
        job.setOrganization(organization);
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setBranch("main");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setTerraformVersion("1.14.0");
        workspace.setIacType("terraform");
        Variable old = new Variable();
        old.setKey("example");
        old.setValue("workspace-default");
        old.setCategory(Category.TERRAFORM);
        Variable untouched = new Variable();
        untouched.setKey("untouched");
        untouched.setValue("workspace-value");
        untouched.setCategory(Category.TERRAFORM);
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(old, untouched)));
        job.setEncryptedVariables(jobVariablesService.encrypt(Map.of("example", "\"private-from-cli\"", "names", "[\"a\", \"b\"]")));
        job.setVariablesAsArguments(true);
        executorService.execute(job, "plan-step", flow(FlowType.terraformPlan));
        job.setTerraformPlan("saved-plan");
        Flow apply = flow(FlowType.terraformApply);
        apply.setRequireSavedPlan(true);
        executorService.execute(job, "apply-step", apply);
        var contexts = ArgumentCaptor.forClass(ExecutorContext.class);
        verify(persistentExecutorService, org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.eq(job), contexts.capture());
        for (ExecutorContext context : contexts.getAllValues()) {
            assertThat(context.getVariables()).containsEntry("example", "private-from-cli")
                    .containsEntry("names", "[\"a\", \"b\"]").containsEntry("untouched", "workspace-value");
            assertThat(context.getEnvironmentVariables()).doesNotContainKeys("TF_VAR_example", "TF_VAR_names");
            // Only the literal that is not a string also goes to the variable file of a newer executor.
            assertThat(context.getHclVariables()).containsOnly(Map.entry("names", "[\"a\", \"b\"]"));
            assertThat(context.toString()).doesNotContain("private-from-cli");
        }
        assertThat(old.getValue()).isEqualTo("workspace-default");
        verify(variableRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unreadableJobVariablesFailTheDispatchWithoutLeakingCiphertext() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("test");
        job.setOrganization(organization);
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setBranch("main");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setTerraformVersion("1.14.0");
        workspace.setIacType("terraform");
        job.setEncryptedVariables("private-invalid-ciphertext");
        assertThatThrownBy(() -> executorService.execute(job, "plan-step", flow(FlowType.terraformPlan)))
                .isInstanceOf(ExecutionException.class)
                .hasMessageNotContaining("private-invalid-ciphertext");
    }

    @Test
    void dynamicCredentialStepsCarryTheirRunPhase() throws Exception {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("test");
        job.setOrganization(organization);
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setBranch("main");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setTerraformVersion("1.14.0");
        workspace.setIacType("terraform");
        Variable enableVault = new Variable();
        enableVault.setKey("ENABLE_DYNAMIC_CREDENTIALS_VAULT");
        enableVault.setValue("true");
        enableVault.setCategory(Category.ENV);
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(enableVault)));
        when(dynamicCredentialsService.runPhase(FlowType.terraformPlan.name())).thenReturn("plan");
        when(dynamicCredentialsService.generateDynamicCredentialsVault(org.mockito.ArgumentMatchers.eq(job),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("plan")))
                .thenAnswer(invocation -> invocation.getArgument(1));

        executorService.execute(job, "plan-step", flow(FlowType.terraformPlan));

        var context = ArgumentCaptor.forClass(ExecutorContext.class);
        verify(persistentExecutorService).send(org.mockito.ArgumentMatchers.eq(job), context.capture());
        assertThat(context.getValue().getEnvironmentVariables())
                .containsEntry("TERRAKUBE_RUN_PHASE", "plan")
                .containsEntry("TF_VAR_terrakube_run_phase", "plan");
    }
}
