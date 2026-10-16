package io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.terrakube.api.plugin.hardened.HardenedModeProperties;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HardenedEphemeralJobTest {

    KubernetesClient client = mock(KubernetesClient.class);
    EphemeralConfiguration config = new EphemeralConfiguration();
    HardenedModeProperties hardened = new HardenedModeProperties();

    @BeforeEach
    void setup() {
        config.setNamespace("terrakube");
        config.setImage("executor:test");
        config.setSecret(List.of("terrakube-executor-secrets"));
        config.setNodeSelector(Map.of("pool", "runners"));
        config.setActiveDeadlineSeconds(3600L);
        hardened.setEnabled(true);
        hardened.setRunnerServiceAccount("terrakube-runner");
        hardened.setRunnerConfigMap("terrakube-runner-config");
    }

    private ExecutorContext context(Map<String, String> workspaceVariables) {
        return ExecutorContext.builder()
                .organizationId("org")
                .workspaceId("ws")
                .jobId("1")
                .stepId("step")
                .runnerToken("a.b.c")
                .environmentVariables(new HashMap<>(workspaceVariables))
                .build();
    }

    @Test
    void workspaceVariablesCannotShapeTheRunnerPod() throws Exception {
        Map<String, String> hostile = new HashMap<>();
        hostile.put("EPHEMERAL_CONFIG_SERVICE_ACCOUNT", "terrakube-api");
        hostile.put("EPHEMERAL_JOB_ENV_VARS", "TerraformStateType=AwsTerraformStateImpl;TerrakubeHardenedMode=false");
        hostile.put("EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP", "other-config");
        hostile.put("EPHEMERAL_CONFIG_MAP_NAME", "other-config");
        hostile.put("TF_PLUGIN_CACHE_DIR", "/cache");
        hostile.put("PVC_CLAIM_NAME", "other-pvc");
        hostile.put("EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT", "runAsUser=0;runAsNonRoot=false");
        hostile.put("EPHEMERAL_CONFIG_SECURITY_CONTEXT", "allowPrivilegeEscalation=true");
        hostile.put("EPHEMERAL_CONFIG_POD_ANNOTATIONS", "eks.amazonaws.com/role-arn=arn:aws:iam::1:role/admin");
        hostile.put("EPHEMERAL_CONFIG_NODE_SELECTOR_TAGS", "pool=system");
        hostile.put("EPHEMERAL_CONFIG_TOLERATIONS", "node-role.kubernetes.io/control-plane:Exists:NoSchedule");
        hostile.put("EPHEMERAL_MEMORY_LIMIT", "512Gi");

        Job job = new EphemeralExecutorService(client, config, hardened).buildHardenedJob("job-1", context(hostile));
        PodSpec pod = job.getSpec().getTemplate().getSpec();
        Container container = pod.getContainers().get(0);
        Map<String, String> env = container.getEnv().stream().filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        EnvVar jobData = container.getEnv().stream().filter(e -> e.getName().equals("EphemeralJobData")).findFirst().orElseThrow();

        assertEquals("terrakube-runner", pod.getServiceAccountName());
        assertFalse(pod.getAutomountServiceAccountToken());
        assertFalse(pod.getEnableServiceLinks());
        assertEquals(Map.of("pool", "runners"), pod.getNodeSelector());
        assertTrue(pod.getTolerations() == null || pod.getTolerations().isEmpty());
        assertTrue(pod.getVolumes() == null || pod.getVolumes().isEmpty());
        assertTrue(pod.getSecurityContext().getRunAsNonRoot());
        assertFalse(container.getSecurityContext().getAllowPrivilegeEscalation());
        assertEquals(List.of("ALL"), container.getSecurityContext().getCapabilities().getDrop());
        assertNull(container.getResources());
        assertTrue(job.getSpec().getTemplate().getMetadata().getAnnotations() == null
                || job.getSpec().getTemplate().getMetadata().getAnnotations().isEmpty());

        assertEquals(1, container.getEnvFrom().size());
        assertNull(container.getEnvFrom().get(0).getSecretRef());
        assertEquals("terrakube-runner-config", container.getEnvFrom().get(0).getConfigMapRef().getName());
        assertEquals("true", env.get("TerrakubeHardenedMode"));
        assertEquals("ApiTerraformStateImpl", env.get("TerraformStateType"));
        assertEquals("true", env.get("LogViaApi"));
        assertEquals(4, env.size());
        assertNull(jobData.getValue());
        assertEquals("job-1", jobData.getValueFrom().getSecretKeyRef().getName());
        assertEquals("EphemeralJobData", jobData.getValueFrom().getSecretKeyRef().getKey());

        io.fabric8.kubernetes.api.model.Secret secret = new EphemeralExecutorService(client, config, hardened).buildHardenedSecret("job-1", context(hostile));
        assertEquals("job-1", secret.getMetadata().getName());
        String payload = new String(java.util.Base64.getDecoder().decode(secret.getStringData().get("EphemeralJobData")));
        assertTrue(payload.contains("\"runnerToken\":\"a.b.c\""));

        assertEquals(0, job.getSpec().getBackoffLimit());
        assertEquals(3600L, job.getSpec().getActiveDeadlineSeconds());
        assertEquals("true", job.getMetadata().getLabels().get(EphemeralExecutorService.HARDENED_LABEL));
        verifyNoInteractions(client);
    }

    @Test
    void refusesAJobWithoutRunnerToken() {
        ExecutorContext context = context(Map.of());
        context.setRunnerToken(null);
        assertThrows(ExecutionException.class,
                () -> new EphemeralExecutorService(client, config, hardened).buildHardenedJob("job-1", context));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private KubernetesClient cluster(Job created, RuntimeException jobFailure, RuntimeException secretFailure,
                                     io.fabric8.kubernetes.client.dsl.Resource secret) {
        KubernetesClient cluster = mock(KubernetesClient.class);
        io.fabric8.kubernetes.client.dsl.MixedOperation secrets = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation secretsInNamespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        org.mockito.Mockito.when(cluster.secrets()).thenReturn(secrets);
        org.mockito.Mockito.when(secrets.inNamespace("terrakube")).thenReturn(secretsInNamespace);
        org.mockito.Mockito.when(secretsInNamespace.resource(org.mockito.ArgumentMatchers.any())).thenReturn(secret);
        org.mockito.Mockito.when(secretsInNamespace.withName(org.mockito.ArgumentMatchers.anyString())).thenReturn(secret);
        if (secretFailure != null) {
            org.mockito.Mockito.when(secret.serverSideApply()).thenThrow(secretFailure);
        }
        io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL batch = mock(io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL.class);
        io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL v1 = mock(io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL.class);
        io.fabric8.kubernetes.client.dsl.MixedOperation jobs = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation jobsInNamespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.ScalableResource jobResource = mock(io.fabric8.kubernetes.client.dsl.ScalableResource.class);
        org.mockito.Mockito.when(cluster.batch()).thenReturn(batch);
        org.mockito.Mockito.when(batch.v1()).thenReturn(v1);
        org.mockito.Mockito.when(v1.jobs()).thenReturn(jobs);
        org.mockito.Mockito.when(jobs.inNamespace("terrakube")).thenReturn(jobsInNamespace);
        org.mockito.Mockito.when(jobsInNamespace.resource(org.mockito.ArgumentMatchers.any())).thenReturn(jobResource);
        if (jobFailure != null) {
            org.mockito.Mockito.when(jobResource.serverSideApply()).thenThrow(jobFailure);
        } else {
            org.mockito.Mockito.when(jobResource.serverSideApply()).thenReturn(created);
        }
        return cluster;
    }

    private io.terrakube.api.rs.job.Job job() {
        io.terrakube.api.rs.job.Job job = new io.terrakube.api.rs.job.Job();
        job.setId(1);
        return job;
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sendStoresThePayloadInASecretOwnedByTheJob() throws Exception {
        io.fabric8.kubernetes.client.dsl.Resource secret = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        Job created = new io.fabric8.kubernetes.api.model.batch.v1.JobBuilder().withNewMetadata().withName("job-x").withUid("uid-1").endMetadata().build();
        new EphemeralExecutorService(cluster(created, null, null, secret), config, hardened).send(job(), context(Map.of()));

        org.mockito.Mockito.verify(secret).serverSideApply();
        org.mockito.ArgumentCaptor<java.util.function.UnaryOperator> edit = org.mockito.ArgumentCaptor.forClass(java.util.function.UnaryOperator.class);
        org.mockito.Mockito.verify(secret).edit(edit.capture());
        io.fabric8.kubernetes.api.model.Secret owned = (io.fabric8.kubernetes.api.model.Secret) edit.getValue()
                .apply(new io.fabric8.kubernetes.api.model.SecretBuilder().withNewMetadata().withName("job-x").endMetadata().build());
        assertEquals("uid-1", owned.getMetadata().getOwnerReferences().get(0).getUid());
        assertEquals("Job", owned.getMetadata().getOwnerReferences().get(0).getKind());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ownershipFailuresAndMissingUidsAreTolerated() throws Exception {
        io.fabric8.kubernetes.client.dsl.Resource secret = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        org.mockito.Mockito.when(secret.edit(org.mockito.ArgumentMatchers.any(java.util.function.UnaryOperator.class)))
                .thenThrow(new io.fabric8.kubernetes.client.KubernetesClientException("conflict", 409, null));
        Job created = new io.fabric8.kubernetes.api.model.batch.v1.JobBuilder().withNewMetadata().withName("job-x").withUid("uid-1").endMetadata().build();
        new EphemeralExecutorService(cluster(created, null, null, secret), config, hardened).send(job(), context(Map.of()));
        new EphemeralExecutorService(cluster(null, null, null, secret), config, hardened).send(job(), context(Map.of()));
        new EphemeralExecutorService(cluster(new Job(), null, null, secret), config, hardened).send(job(), context(Map.of()));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aRejectedJobRemovesItsSecret() {
        io.fabric8.kubernetes.client.dsl.Resource secret = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        KubernetesClient cluster = cluster(null, new io.fabric8.kubernetes.client.KubernetesClientException("bad", 400, null), null, secret);
        assertThrows(ExecutionException.class, () -> new EphemeralExecutorService(cluster, config, hardened).send(job(), context(Map.of())));
        org.mockito.Mockito.verify(secret).delete();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void rejectedSecretsStopTheDispatch() {
        io.fabric8.kubernetes.client.dsl.Resource secret = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        assertThrows(ExecutionException.class, () -> new EphemeralExecutorService(
                cluster(null, null, new io.fabric8.kubernetes.client.KubernetesClientException("forbidden", 403, null), secret),
                config, hardened).send(job(), context(Map.of())));
        assertThrows(io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorUnavailableException.class, () -> new EphemeralExecutorService(
                cluster(null, null, new io.fabric8.kubernetes.client.KubernetesClientException("down", 503, null),
                        mock(io.fabric8.kubernetes.client.dsl.Resource.class)),
                config, hardened).send(job(), context(Map.of())));
    }
}
