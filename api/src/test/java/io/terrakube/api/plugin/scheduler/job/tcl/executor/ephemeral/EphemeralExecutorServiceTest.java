package io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL;
import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorUnavailableException;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

@SuppressWarnings({ "rawtypes", "unchecked" })
@ExtendWith(MockitoExtension.class)
public class EphemeralExecutorServiceTest {
    KubernetesClient client;
    EphemeralConfiguration config = new EphemeralConfiguration();

    BatchAPIGroupDSL batch = mock(BatchAPIGroupDSL.class);
    V1BatchAPIGroupDSL v1 = mock(V1BatchAPIGroupDSL.class);
    MixedOperation jobs = mock(MixedOperation.class);
    NonNamespaceOperation namespaced = mock(NonNamespaceOperation.class);
    ScalableResource resource = mock(ScalableResource.class);

    @Captor
    ArgumentCaptor<io.fabric8.kubernetes.api.model.batch.v1.Job> job;

    @BeforeEach
    void setup() {
        client = mock(KubernetesClient.class, new FailUnkownMethod<KubernetesClient>());
        doReturn(batch).when(client).batch();
        doReturn(v1).when(batch).v1();
        doReturn(jobs).when(v1).jobs();
        doReturn(namespaced).when(jobs).inNamespace(anyString());
        doReturn(resource).when(namespaced).resource(any());
        doReturn(null).when(resource).serverSideApply();

        Map<String, String> selector = new HashMap<>();
        selector.put("some", "node");
        config.setNodeSelector(selector);
        config.setNamespace("ze-namespace");
        config.setImage("ze-image:ze-label");
        config.setSecret(List.of("ze-secret"));
    }

    private EphemeralExecutorService subject() {
        return new EphemeralExecutorService(client, config);
    }

    private Job job() {
        Job job = new Job();
        job.setStatus(JobStatus.pending);
        job.setId(4711);
        return job;
    }

    private ExecutorContext context() {
        return ExecutorContext.builder()
                .branch("ze-branch")
                .organizationId("ze-org")
                .workspaceId("ze-workspace")
                .environmentVariables(new HashMap<>())
                .build();
    }

    private HashMap<String, String> envVarsToMap(List<EnvVar> envVars) {
        HashMap<String, String> map = new HashMap<>();
        for (EnvVar envVar : envVars) {
            map.put(envVar.getName(), envVar.getValue());
        }
        return map;
    }

    @Test
    public void setImage() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals("ze-secret", container.getEnvFrom().getFirst().getSecretRef().getName());
    }

    @Test
    public void mountsSecretAsEnvVars() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals("ze-image:ze-label", container.getImage());
    }

    @Test
    public void mountsMultipleSecretsAsEnvVars() throws ExecutionException {
        config.setSecret(List.of("secret-one", "secret-two"));

        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals(2, container.getEnvFrom().size());
        assertEquals("secret-one", container.getEnvFrom().get(0).getSecretRef().getName());
        assertEquals("secret-two", container.getEnvFrom().get(1).getSecretRef().getName());
    }

    @Test
    public void mountsEmptySecretListCleanly() throws ExecutionException {
        config.setSecret(List.of());

        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertTrue(container.getEnvFrom().isEmpty());
    }

    @Test
    public void mountsConfigMapAsEnvFrom() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP", "ze-configmap");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals(2, container.getEnvFrom().size());
        assertEquals("ze-secret", container.getEnvFrom().get(0).getSecretRef().getName());
        assertEquals("ze-configmap", container.getEnvFrom().get(1).getConfigMapRef().getName());
    }

    @Test
    public void mountsMultipleConfigMapsAsEnvFrom() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP", "cm-one, cm-two");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals(3, container.getEnvFrom().size());
        assertEquals("cm-one", container.getEnvFrom().get(1).getConfigMapRef().getName());
        assertEquals("cm-two", container.getEnvFrom().get(2).getConfigMapRef().getName());
    }

    @Test
    public void setsPodTemplateAnnotations() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_POD_ANNOTATIONS", "vault.hashicorp.com/agent-inject=true;vault.hashicorp.com/role=terrakube");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Map<String, String> podAnnotations = job.getValue().getSpec().getTemplate().getMetadata().getAnnotations();
        assertEquals("true", podAnnotations.get("vault.hashicorp.com/agent-inject"));
        assertEquals("terrakube", podAnnotations.get("vault.hashicorp.com/role"));
    }

    @Test
    public void setsPodTemplateMetadataWithLabelsWhenNoAnnotations() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        ObjectMeta metadata = job.getValue().getSpec().getTemplate().getMetadata();
        assertNotNull(metadata);
        assertEquals("ze-org", metadata.getLabels().get("terrakube.io/organization"));
        assertEquals("ze-workspace", metadata.getLabels().get("terrakube.io/workspace"));
        assertTrue(metadata.getAnnotations() == null || metadata.getAnnotations().isEmpty());
    }

    @Test
    public void setsLabelsOnJob() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Map<String, String> labels = job.getValue().getMetadata().getLabels();
        assertEquals("ze-org", labels.get("terrakube.io/organization"));
        assertEquals("ze-workspace", labels.get("terrakube.io/workspace"));
    }

    @Test
    public void setsLabelsOnPodTemplate() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Map<String, String> labels = job.getValue().getSpec().getTemplate().getMetadata().getLabels();
        assertEquals("ze-org", labels.get("terrakube.io/organization"));
        assertEquals("ze-workspace", labels.get("terrakube.io/workspace"));
    }

    @Test
    public void propagatesCustomLabelsToPodTemplate() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_LABELS", "team=platform;env=prod");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Map<String, String> jobLabels = job.getValue().getMetadata().getLabels();
        Map<String, String> podLabels = job.getValue().getSpec().getTemplate().getMetadata().getLabels();
        assertEquals("platform", jobLabels.get("team"));
        assertEquals("prod", jobLabels.get("env"));
        assertEquals("platform", podLabels.get("team"));
        assertEquals("prod", podLabels.get("env"));
    }

    @Test
    public void setsJobName() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        assertEquals("job-4711-", job.getValue().getMetadata().getName().substring(0, 9));
    }

    @Test
    public void passesJobDataToJob() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        Map<String, String> envVars = envVarsToMap(container.getEnv());
        String jobData = new String(Base64.getDecoder().decode(envVars.get("EphemeralJobData")));
        assertTrue(jobData.contains("ze-branch"));
    }

    @Test
    public void passesAdditionalEnvVarsToJob() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_JOB_ENV_VARS", "FOO=bar; BAZ=quux");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        Map<String, String> envVars = envVarsToMap(container.getEnv());
        assertEquals("bar", envVars.get("FOO"));
        assertEquals("quux", envVars.get("BAZ"));
    }

    @Test
    public void defaultsNodeSelectorToConfig() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        PodSpec podspec = job.getValue().getSpec().getTemplate().getSpec();
        assertEquals("node", podspec.getNodeSelector().get("some"));
    }

    @Test
    public void overridesNodeSelectorsFromEnvVars() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_NODE_SELECTOR_TAGS", "another=node");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        PodSpec podspec = job.getValue().getSpec().getTemplate().getSpec();
        assertEquals("node", podspec.getNodeSelector().get("another"));
        assertEquals(null, podspec.getNodeSelector().get("some"));
    }

    @Test
    public void defaultsOptionalJobControlsToKubernetesOwnDefaults() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        assertNull(job.getValue().getSpec().getActiveDeadlineSeconds());
        assertNull(job.getValue().getSpec().getBackoffLimit());
        assertEquals(30, job.getValue().getSpec().getTtlSecondsAfterFinished());
        // Unlike the other three, this one does NOT inherit Kubernetes' own default (30s):
        // it defaults to 60 in EphemeralConfiguration itself.
        assertEquals(60L, job.getValue().getSpec().getTemplate().getSpec().getTerminationGracePeriodSeconds());
    }

    @Test
    public void appliesJobControlsWhenConfigured() throws ExecutionException {
        config.setActiveDeadlineSeconds(3600L);
        config.setBackoffLimit(2);
        config.setTtlSecondsAfterFinished(60);
        config.setTerminationGracePeriodSeconds(90L);

        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        assertEquals(3600L, job.getValue().getSpec().getActiveDeadlineSeconds());
        assertEquals(2, job.getValue().getSpec().getBackoffLimit());
        assertEquals(60, job.getValue().getSpec().getTtlSecondsAfterFinished());
        assertEquals(90L, job.getValue().getSpec().getTemplate().getSpec().getTerminationGracePeriodSeconds());
    }

    @Test
    public void appliesOnlyTheJobControlThatIsConfigured() throws ExecutionException {
        // terminationGracePeriodSeconds lives on the pod template spec, the other three on the job
        // spec, so this also guards against a value being written to the wrong node.
        config.setTerminationGracePeriodSeconds(90L);

        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        assertEquals(90L, job.getValue().getSpec().getTemplate().getSpec().getTerminationGracePeriodSeconds());
        assertNull(job.getValue().getSpec().getActiveDeadlineSeconds());
        assertNull(job.getValue().getSpec().getBackoffLimit());
    }

    @Test
    public void setResources() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CPU_REQUEST", "100m");
        context.getEnvironmentVariables().put("EPHEMERAL_MEMORY_REQUEST", "50Mi");
        context.getEnvironmentVariables().put("EPHEMERAL_STORAGE_REQUEST", "2Gi");
        context.getEnvironmentVariables().put("EPHEMERAL_CPU_LIMIT", "200m");
        context.getEnvironmentVariables().put("EPHEMERAL_MEMORY_LIMIT", "100Mi");
        context.getEnvironmentVariables().put("EPHEMERAL_STORAGE_LIMIT", "4Gi");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals("100m", container.getResources().getRequests().get("cpu").toString());
        assertEquals("200m", container.getResources().getLimits().get("cpu").toString());
        assertEquals("50Mi", container.getResources().getRequests().get("memory").toString());
        assertEquals("100Mi", container.getResources().getLimits().get("memory").toString());
        assertEquals("2Gi", container.getResources().getRequests().get("ephemeral-storage").toString());
        assertEquals("4Gi", container.getResources().getLimits().get("ephemeral-storage").toString());
    }

    @Test
    public void setStorageResourcesOnly() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_STORAGE_REQUEST", "2Gi");
        context.getEnvironmentVariables().put("EPHEMERAL_STORAGE_LIMIT", "4Gi");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals("2Gi", container.getResources().getRequests().get("ephemeral-storage").toString());
        assertEquals("4Gi", container.getResources().getLimits().get("ephemeral-storage").toString());
        assertNull(container.getResources().getRequests().get("cpu"));
        assertNull(container.getResources().getLimits().get("cpu"));
    }

    @Test
    public void appliesTolerations() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_TOLERATIONS", "foo=bar");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Toleration toleration = job.getValue().getSpec().getTemplate().getSpec().getTolerations().getFirst();
        assertEquals("foo", toleration.getKey());
        assertEquals("bar", toleration.getValue());
        assertEquals("Exists", toleration.getOperator());
    }

    @Test
    public void appliesSecurityContext() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT", "runAsNonRoot=true");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        PodSpec podspec = job.getValue().getSpec().getTemplate().getSpec();
        assertEquals(Boolean.TRUE, podspec.getSecurityContext().getRunAsNonRoot());
    }

    @Test
    public void errorsOnBadSecurityContextConfig() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT", "nonsense");
        // Keep strict mock happy
        client.batch().v1().jobs().inNamespace("null").resource(null).serverSideApply();

        assertThrows(ExecutionException.class, () -> subject().send(job(), context));
    }

    @Test
    public void mountsConfigMapAtExplicitPath() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_MAP_NAME", "ze-map");
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_MAP_MOUNT_PATH", "/ze-path");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Volume volume = job.getValue().getSpec().getTemplate().getSpec().getVolumes().getFirst();
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        VolumeMount mount = container.getVolumeMounts().getFirst();
        assertEquals("ze-map", volume.getConfigMap().getName());
        assertEquals("/ze-path", mount.getMountPath());
    }

    @Test
    public void mountsConfigMapAtDefaultPath() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CONFIG_MAP_NAME", "ze-map");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals("/data", container.getVolumeMounts().getFirst().getMountPath());
    }

    @Test
    public void propagatesClientErrors() throws ExecutionException {
        doThrow(new KubernetesClientException("Boom!")).when(resource).serverSideApply();

        assertThrows(ExecutionException.class, () -> subject().send(job(), context()));
    }

    @Test
    public void retriesOnConnectionLevelFailure() {
        // No HTTP response reached the apiserver at all - code defaults to 0.
        doThrow(new KubernetesClientException("connection refused")).when(resource).serverSideApply();

        assertThrows(ExecutorUnavailableException.class, () -> subject().send(job(), context()));
    }

    @Test
    public void retriesOnThrottling() {
        doThrow(new KubernetesClientException("Too Many Requests", 429, null)).when(resource).serverSideApply();

        assertThrows(ExecutorUnavailableException.class, () -> subject().send(job(), context()));
    }

    @Test
    public void retriesOnServerError() {
        doThrow(new KubernetesClientException("etcd unavailable", 503, null)).when(resource).serverSideApply();

        assertThrows(ExecutorUnavailableException.class, () -> subject().send(job(), context()));
    }

    @Test
    public void retriesOnResourceQuotaExceeded() {
        doThrow(new KubernetesClientException(
                        "jobs.batch is forbidden: exceeded quota: compute-quota, requested: pods=1, used: pods=5, limited: pods=5",
                        403, null))
                .when(resource).serverSideApply();

        assertThrows(ExecutorUnavailableException.class, () -> subject().send(job(), context()));
    }

    @Test
    public void failsFastOnRbacDenial() {
        doThrow(new KubernetesClientException(
                        "jobs.batch is forbidden: User \"system:serviceaccount:ns:sa\" cannot create resource \"jobs\"",
                        403, null))
                .when(resource).serverSideApply();

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> subject().send(job(), context()));
        assertFalse(thrown instanceof ExecutorUnavailableException);
    }

    @Test
    public void failsFastOnBadRequest() {
        doThrow(new KubernetesClientException("invalid quantity for resource cpu", 400, null))
                .when(resource).serverSideApply();

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> subject().send(job(), context()));
        assertFalse(thrown instanceof ExecutorUnavailableException);
    }

    @Test
    public void mountsSecretAsEphemeralEmptyDir() throws ExecutionException {
        config.setEmptyDirSecret(true);

        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        PodSpec podSpec = job.getValue().getSpec().getTemplate().getSpec();
        Container container = podSpec.getContainers().getFirst();

        // 1. Secrets must NOT be present in envFrom
        assertTrue(container.getEnvFrom().isEmpty(), "Secrets must not be present in envFrom when emptyDirSecret is true");

        // 2. ExecutorSecretsDirectory env var must be set
        HashMap<String, String> envMap = envVarsToMap(container.getEnv());
        assertEquals("/etc/terrakube/secrets", envMap.get("ExecutorSecretsDirectory"));

        // 3. Container mounts writable emptyDir at /etc/terrakube/secrets
        VolumeMount secretMount = container.getVolumeMounts().stream()
                .filter(m -> "/etc/terrakube/secrets".equals(m.getMountPath()))
                .findFirst()
                .orElse(null);
        assertNotNull(secretMount);
        assertEquals("writable-executor-secrets", secretMount.getName());

        // 4. Init container is present and copies secrets
        assertNotNull(podSpec.getInitContainers());
        assertEquals(1, podSpec.getInitContainers().size());
        Container initContainer = podSpec.getInitContainers().getFirst();
        assertEquals("copy-executor-secrets", initContainer.getName());
        assertEquals("busybox:1.37.0", initContainer.getImage());
        assertNotNull(initContainer.getSecurityContext());
        assertTrue(initContainer.getSecurityContext().getRunAsNonRoot());
        assertEquals(1000L, initContainer.getSecurityContext().getRunAsUser());
        assertEquals(1000L, initContainer.getSecurityContext().getRunAsGroup());
        assertFalse(initContainer.getSecurityContext().getAllowPrivilegeEscalation());
        assertEquals(List.of("ALL"), initContainer.getSecurityContext().getCapabilities().getDrop());
        assertEquals("RuntimeDefault", initContainer.getSecurityContext().getSeccompProfile().getType());
        assertNotNull(initContainer.getResources());
        assertEquals(new Quantity("50m"), initContainer.getResources().getRequests().get("cpu"));
        assertEquals(new Quantity("64Mi"), initContainer.getResources().getRequests().get("memory"));
        assertEquals(new Quantity("100m"), initContainer.getResources().getLimits().get("cpu"));
        assertEquals(new Quantity("64Mi"), initContainer.getResources().getLimits().get("memory"));
        assertTrue(initContainer.getArgs().getFirst().contains("chmod 0700 /config-rw"));
        assertTrue(initContainer.getArgs().getFirst().contains("chmod 0600"));

        // 5. Pod volumes include raw projected secret and memory-backed emptyDir
        Volume rawVol = podSpec.getVolumes().stream()
                .filter(v -> "raw-secret".equals(v.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(rawVol);
        assertNotNull(rawVol.getProjected());
        assertEquals(1, rawVol.getProjected().getSources().size());
        assertEquals("ze-secret", rawVol.getProjected().getSources().getFirst().getSecret().getName());

        Volume writableVol = podSpec.getVolumes().stream()
                .filter(v -> "writable-executor-secrets".equals(v.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(writableVol);
        assertNotNull(writableVol.getEmptyDir());
        assertEquals("Memory", writableVol.getEmptyDir().getMedium());
        assertEquals(new Quantity("64Mi"), writableVol.getEmptyDir().getSizeLimit());
    }

    @Test
    public void mountsMultipleSecretsAsEphemeralEmptyDir() throws ExecutionException {
        config.setEmptyDirSecret(true);
        config.setSecret(List.of("secret-one", "secret-two"));

        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        PodSpec podSpec = job.getValue().getSpec().getTemplate().getSpec();
        Container container = podSpec.getContainers().getFirst();

        assertTrue(container.getEnvFrom().isEmpty());

        Volume rawVol = podSpec.getVolumes().stream()
                .filter(v -> "raw-secret".equals(v.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(rawVol);
        assertNotNull(rawVol.getProjected());
        assertEquals(2, rawVol.getProjected().getSources().size());
        assertEquals("secret-one", rawVol.getProjected().getSources().get(0).getSecret().getName());
        assertEquals("secret-two", rawVol.getProjected().getSources().get(1).getSecret().getName());
    }

    @Test
    public void mountsEmptySecretListWithEmptyDirSecretCleanly() throws ExecutionException {
        config.setEmptyDirSecret(true);
        config.setSecret(List.of());

        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        PodSpec podSpec = job.getValue().getSpec().getTemplate().getSpec();
        Container container = podSpec.getContainers().getFirst();

        assertTrue(container.getEnvFrom().isEmpty());
        assertTrue(podSpec.getInitContainers() == null || podSpec.getInitContainers().isEmpty());
    }
}
