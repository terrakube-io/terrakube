package io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.fabric8.kubernetes.api.model.PodSpec;
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
        config.setSecret("ze-secret");
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
    public void setsLabelsOnJob() throws ExecutionException {
        subject().send(job(), context());

        verify(namespaced, times(1)).resource(job.capture());
        Map<String, String> labels = job.getValue().getMetadata().getLabels();
        assertEquals("ze-org", labels.get("terrakube.io/organization"));
        assertEquals("ze-workspace", labels.get("terrakube.io/workspace"));
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
    public void setResources() throws ExecutionException {
        ExecutorContext context = context();
        context.getEnvironmentVariables().put("EPHEMERAL_CPU_REQUEST", "100m");
        context.getEnvironmentVariables().put("EPHEMERAL_MEMORY_REQUEST", "50Mi");
        context.getEnvironmentVariables().put("EPHEMERAL_CPU_LIMIT", "200m");
        context.getEnvironmentVariables().put("EPHEMERAL_MEMORY_LIMIT", "100Mi");

        subject().send(job(), context);

        verify(namespaced, times(1)).resource(job.capture());
        Container container = job.getValue().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertEquals("100m", container.getResources().getRequests().get("cpu").toString());
        assertEquals("200m", container.getResources().getLimits().get("cpu").toString());
        assertEquals("50Mi", container.getResources().getRequests().get("memory").toString());
        assertEquals("100Mi", container.getResources().getLimits().get("memory").toString());
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
}
