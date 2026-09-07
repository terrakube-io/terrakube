package io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorUnavailableException;
import io.terrakube.api.rs.job.Job;

import java.util.*;

@Slf4j
@Service
@AllArgsConstructor
public class EphemeralExecutorService {

    private static final String NODE_SELECTOR = "EPHEMERAL_CONFIG_NODE_SELECTOR_TAGS";
    private static final String TOLERATIONS = "EPHEMERAL_CONFIG_TOLERATIONS";
    private static final String SERVICE_ACCOUNT = "EPHEMERAL_CONFIG_SERVICE_ACCOUNT";
    private static final String ANNOTATIONS = "EPHEMERAL_CONFIG_ANNOTATIONS";
    private static final String CONFIG_MAP_NAME = "EPHEMERAL_CONFIG_MAP_NAME";
    private static final String CONFIG_MAP_PATH = "EPHEMERAL_CONFIG_MAP_MOUNT_PATH";
    private static final String TF_CACHE_DIR = "TF_PLUGIN_CACHE_DIR";
    private static final String PVC_CLAIM_NAME = "PVC_CLAIM_NAME";
    private static final String POD_SECURITY_CONTEXT = "EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT";
    private static final String SECURITY_CONTEXT = "EPHEMERAL_CONFIG_SECURITY_CONTEXT";
    private static final String EPHEMERAL_CPU_REQUEST = "EPHEMERAL_CPU_REQUEST";
    private static final String EPHEMERAL_MEMORY_REQUEST = "EPHEMERAL_MEMORY_REQUEST";
    private static final String EPHEMERAL_STORAGE_REQUEST = "EPHEMERAL_STORAGE_REQUEST";
    private static final String EPHEMERAL_CPU_LIMIT = "EPHEMERAL_CPU_LIMIT";
    private static final String EPHEMERAL_MEMORY_LIMIT = "EPHEMERAL_MEMORY_LIMIT";
    private static final String EPHEMERAL_STORAGE_LIMIT = "EPHEMERAL_STORAGE_LIMIT";
    private static final String EPHEMERAL_JOB_ENV_VARS = "EPHEMERAL_JOB_ENV_VARS";
    private static final String LABELS = "EPHEMERAL_CONFIG_LABELS";
    private static final String ENVFROM_CONFIG_MAP = "EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP";
    private static final String POD_ANNOTATIONS = "EPHEMERAL_CONFIG_POD_ANNOTATIONS";

    KubernetesClient kubernetesClient;
    EphemeralConfiguration ephemeralConfiguration;

    public void send(Job job, ExecutorContext executorContext) throws ExecutionException {
        final String jobName = String.format("job-%s-%s", job.getId(), System.currentTimeMillis());
        log.info("Ephemeral Executor Image {}, Job: {}, Namespace: {}, NodeSelector: {}", ephemeralConfiguration.getImage(), jobName, ephemeralConfiguration.getNamespace(), ephemeralConfiguration.getNodeSelector());
        final List<EnvFromSource> executorEnvFromSources = new ArrayList<>();
        if (ephemeralConfiguration.getSecret() != null) {
            for (String secretName : ephemeralConfiguration.getSecret()) {
                if (secretName == null) {
                    continue;
                }
                String trimmed = secretName.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                SecretEnvSource secretEnvSource = new SecretEnvSource();
                secretEnvSource.setName(trimmed);
                EnvFromSource envFromSource = new EnvFromSource();
                envFromSource.setSecretRef(secretEnvSource);
                executorEnvFromSources.add(envFromSource);
            }
        }

        String configMapEnvFromNames = getConfigValue(
                executorContext, ENVFROM_CONFIG_MAP, ephemeralConfiguration.getConfigMap().getEnvFrom);
        if (configMapEnvFromNames != null) {
            for (String configMapName : configMapEnvFromNames.split(",")) {
                String trimmed = configMapName.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                ConfigMapEnvSource configMapEnvSource = new ConfigMapEnvSource();
                configMapEnvSource.setName(trimmed);
                EnvFromSource configMapEnvFrom = new EnvFromSource();
                configMapEnvFrom.setConfigMapRef(configMapEnvSource);
                executorEnvFromSources.add(configMapEnvFrom);
            }
        }

        EnvVar executorFlagBatch = new EnvVar();
        executorFlagBatch.setName("EphemeralFlagBatch");
        executorFlagBatch.setValue("true");

        EnvVar executorFlagBatchJsonContent = new EnvVar();
        try {
            executorFlagBatchJsonContent.setName("EphemeralJobData");
            ObjectMapper mapper = new ObjectMapper();
            String jobJson = mapper.writeValueAsString(executorContext);
            executorFlagBatchJsonContent.setValue(Base64.getEncoder().encodeToString(jobJson.getBytes("UTF-8")));
        } catch (Exception e) {
            log.error(e.getMessage());
        }

        final List<EnvVar> executorEnvVarFlags = new LinkedList<>();
        executorEnvVarFlags.add(executorFlagBatch);
        executorEnvVarFlags.add(executorFlagBatchJsonContent);

        String additionalEnvVars = getConfigValue(
                executorContext, EPHEMERAL_JOB_ENV_VARS, ephemeralConfiguration.getJobEnvVars());

        if (additionalEnvVars != null) {
            Map<String, String> parsedEnvVars = parseKeyValueString(additionalEnvVars);
            for (Map.Entry<String, String> entry : parsedEnvVars.entrySet()) {
                EnvVar envVar = new EnvVar();
                envVar.setName(entry.getKey());
                envVar.setValue(entry.getValue());
                executorEnvVarFlags.add(envVar);
            }
        }

        Optional<String> nodeSelector = Optional.ofNullable(executorContext.getEnvironmentVariables().getOrDefault(NODE_SELECTOR, null));
        Map<String, String> nodeSelectorInfo = new HashMap<>();
        log.info("Custom Node selector: {} {}", nodeSelector.isPresent(), nodeSelector.isEmpty());
        if(nodeSelector.isPresent()) {
            nodeSelectorInfo.putAll(parseKeyValueString(nodeSelector.get()));
        } else {
            log.info("Using default node selector information");
            nodeSelectorInfo = ephemeralConfiguration.getNodeSelector();
        }

        String tolerationsInfo = getConfigValue(
                executorContext, TOLERATIONS, ephemeralConfiguration.getTolerations());
        List<Toleration> tolerations = new ArrayList<>();

        if (tolerationsInfo != null) {
            for (String tolerationData : tolerationsInfo.split(";")) {
                String[] info = tolerationData.split(":");
                Toleration toleration = new Toleration();

                if (info[0].contains("=")) {
                    String[] keyValue = info[0].split("=");
                    toleration.setKey(keyValue[0]);
                    toleration.setValue(keyValue[1]);
                } else {
                    toleration.setKey(info[0]);
                }
                
                toleration.setOperator(info.length > 1 ? info[1] : "Exists");
                toleration.setEffect(info.length > 2 ? info[2] : null);

                tolerations.add(toleration);
            }
        }

        String annotationsInfo = getConfigValue(
                executorContext, ANNOTATIONS, ephemeralConfiguration.getAnnotations());
        Map<String, String> annotations = new HashMap<>();
        log.info("Custom Annotations: {}", annotationsInfo != null);
        if (annotationsInfo != null) {
            annotations.putAll(parseKeyValueString(annotationsInfo));
        }

        String podAnnotationsInfo = getConfigValue(
                executorContext, POD_ANNOTATIONS, ephemeralConfiguration.getPodAnnotations());
        Map<String, String> podAnnotations = new HashMap<>();
        if (podAnnotationsInfo != null) {
            podAnnotations.putAll(parseKeyValueString(podAnnotationsInfo));
        }

        String serviceAccount = getConfigValue(
                executorContext, SERVICE_ACCOUNT, ephemeralConfiguration.getServiceAccount());

        // Volume and VolumeMount for ConfigMap if specified
        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> volumeMounts = new ArrayList<>();

        String configMapName = getConfigValue(
                executorContext, CONFIG_MAP_NAME, ephemeralConfiguration.getConfigMap().getName());
        if (configMapName != null) {
            String mountPath = getConfigValue(
                    executorContext, CONFIG_MAP_PATH, ephemeralConfiguration.getConfigMap().getMountPath());
            mountPath = mountPath != null ? mountPath : "/data";  // Default mount path if not specified

            // Define ConfigMap volume
            Volume configMapVolume = new Volume();
            configMapVolume.setName("config-volume");
            ConfigMapVolumeSource configMapVolumeSource = new ConfigMapVolumeSource();
            configMapVolumeSource.setName(configMapName);
            configMapVolume.setConfigMap(configMapVolumeSource);
            volumes.add(configMapVolume);

            // Define VolumeMount for the container
            VolumeMount configMapMount = new VolumeMount();
            configMapMount.setName("config-volume");
            configMapMount.setMountPath(mountPath);
            volumeMounts.add(configMapMount);
        }

        Optional<String> configPVCOpt = Optional.ofNullable(executorContext.getEnvironmentVariables().get(TF_CACHE_DIR));
        if (configPVCOpt.isPresent()) {
            String configPVCpath = configPVCOpt.get();
            String PluginVolumeName = "tf-plugin-volume";
            String pvcClaimName = executorContext.getEnvironmentVariables().getOrDefault(PVC_CLAIM_NAME, "terrakube-plugin-pvc");

            boolean pvcExists = kubernetesClient.persistentVolumeClaims()
                    .inNamespace(ephemeralConfiguration.getNamespace())
                    .withName(pvcClaimName)
                    .get() != null;

            if (pvcExists) {
                log.info("PVC {} exists, attaching to the volume.", pvcClaimName);
                Volume sharedVolume = new Volume();
                sharedVolume.setName(PluginVolumeName);
                PersistentVolumeClaimVolumeSource pvcSource = new PersistentVolumeClaimVolumeSource();
                pvcSource.setClaimName(pvcClaimName);
                sharedVolume.setPersistentVolumeClaim(pvcSource);

                VolumeMount sharedVolumeMount = new VolumeMount();
                sharedVolumeMount.setName(PluginVolumeName);
                sharedVolumeMount.setMountPath(configPVCpath);

                volumes.add(sharedVolume);
                volumeMounts.add(sharedVolumeMount);
            } else {
                log.warn("PVC {} does not exist, skipping volume attachment.", pvcClaimName);
            }
        }

        String configPodSecurityContext = getConfigValue(
                executorContext, POD_SECURITY_CONTEXT, ephemeralConfiguration.getPodSecurityContext());
        PodSecurityContext podSecurityContext = new PodSecurityContextBuilder().withFsGroup(1000L).build();
        if (configPodSecurityContext != null) {
            log.info("Using custom pod security context");
            Map<String, String> podSecurityContextData = parseKeyValueString(configPodSecurityContext);
            for (Map.Entry<String, String> entry : podSecurityContextData.entrySet()) {
                switch (entry.getKey()) {
                    case "fsGroup":
                        podSecurityContext.setFsGroup(Long.parseLong(entry.getValue()));
                        break;
                    case "runAsNonRoot":
                        podSecurityContext.setRunAsNonRoot(Boolean.parseBoolean(entry.getValue()));
                        break;
                    case "runAsUser":
                        podSecurityContext.setRunAsUser(Long.parseLong(entry.getValue()));
                        break;
                    default:
                        throw new ExecutionException(new Throwable(String.format("Unknown value %s for %s", entry.getValue(), POD_SECURITY_CONTEXT)));
                }
            }
        }

        String configSecurityContext = getConfigValue(
                executorContext, SECURITY_CONTEXT, ephemeralConfiguration.getSecurityContext());
        SecurityContext securityContext = new SecurityContext();
        if (configSecurityContext != null) {
            log.info("Using custom security context");
            Map<String, String> securityContextData = parseKeyValueString(configSecurityContext);
            for (Map.Entry<String, String> entry : securityContextData.entrySet()) {
                switch (entry.getKey()) {
                    case "allowPrivilegeEscalation":
                        securityContext.setAllowPrivilegeEscalation(Boolean.parseBoolean(entry.getValue()));
                        break;
                    default:
                        throw new ExecutionException(new Throwable(String.format("Unknown values %s for %s", entry.getValue(), SECURITY_CONTEXT)));
                }
            }
        }

        String cpuRequest = getConfigValue(
                executorContext, EPHEMERAL_CPU_REQUEST, ephemeralConfiguration.getResources().getCpuRequest());
        String memoryRequest = getConfigValue(
                executorContext, EPHEMERAL_MEMORY_REQUEST, ephemeralConfiguration.getResources().getMemoryRequest());
        String storageRequest = getConfigValue(
                executorContext, EPHEMERAL_STORAGE_REQUEST, ephemeralConfiguration.getResources().getEphemeralStorageRequest());
        String cpuLimit = getConfigValue(
                executorContext, EPHEMERAL_CPU_LIMIT, ephemeralConfiguration.getResources().getCpuLimit());
        String memoryLimit = getConfigValue(
                executorContext, EPHEMERAL_MEMORY_LIMIT, ephemeralConfiguration.getResources().getMemoryLimit());
        String storageLimit = getConfigValue(
                executorContext, EPHEMERAL_STORAGE_LIMIT, ephemeralConfiguration.getResources().getEphemeralStorageLimit());

        ResourceRequirementsBuilder resourceBuilder = new ResourceRequirementsBuilder();
        boolean hasResources = false;

        if (cpuRequest != null) {
            resourceBuilder.addToRequests("cpu", new Quantity(cpuRequest));
            hasResources = true;
        }
        if (memoryRequest != null) {
            resourceBuilder.addToRequests("memory", new Quantity(memoryRequest));
            hasResources = true;
        }
        if (storageRequest != null) {
            resourceBuilder.addToRequests("ephemeral-storage", new Quantity(storageRequest));
            hasResources = true;
        }
        if (cpuLimit != null) {
            resourceBuilder.addToLimits("cpu", new Quantity(cpuLimit));
            hasResources = true;
        }
        if (memoryLimit != null) {
            resourceBuilder.addToLimits("memory", new Quantity(memoryLimit));
            hasResources = true;
        }
        if (storageLimit != null) {
            resourceBuilder.addToLimits("ephemeral-storage", new Quantity(storageLimit));
            hasResources = true;
        }

        String labelsInfo = getConfigValue(
                executorContext, LABELS, ephemeralConfiguration.getLabels());
        Map<String, String> labels = new HashMap<>();
        log.info("Custom Labels: {}", labelsInfo != null);
        if (labelsInfo != null) {
            labels.putAll(parseKeyValueString(labelsInfo));
        }

        labels.put("terrakube.io/organization", executorContext.getOrganizationId());
        labels.put("terrakube.io/workspace", executorContext.getWorkspaceId());

        JobBuilder jobBuilder = new JobBuilder()
                .withApiVersion("batch/v1")
                .withNewMetadata()
                .withName(jobName)
                .withLabels(labels)
                .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .withSecurityContext(podSecurityContext)
                .withNodeSelector(nodeSelectorInfo)
                .withServiceAccountName(serviceAccount)
                .withTolerations(tolerations)
                .withVolumes(volumes)
                .addNewContainer()
                .withName("executor")
                .withEnvFrom(executorEnvFromSources)
                .withImage(ephemeralConfiguration.getImage())
                .withEnv(executorEnvVarFlags)
                .withVolumeMounts(volumeMounts)
                .withSecurityContext(securityContext)
                .withResources(hasResources ? resourceBuilder.build() : null)
                .endContainer()
                .withRestartPolicy("Never")
                .endSpec()
                .endTemplate()
                .withTtlSecondsAfterFinished(30)
                .endSpec();

        if (!podAnnotations.isEmpty()) {
            jobBuilder.editSpec().editTemplate().editOrNewMetadata()
                    .addToAnnotations(podAnnotations)
                    .endMetadata().endTemplate().endSpec();
        }

        if (!labels.isEmpty()) {
            jobBuilder.editSpec().editTemplate().editOrNewMetadata()
                    .addToLabels(labels)
                    .endMetadata().endTemplate().endSpec();
        }

        io.fabric8.kubernetes.api.model.batch.v1.Job k8sJob = jobBuilder.build();

        try {
            kubernetesClient.batch().v1().jobs().inNamespace(ephemeralConfiguration.getNamespace()).resource(k8sJob).serverSideApply();
        } catch (KubernetesClientException e) {
            if (isRetryable(e)) {
                throw new ExecutorUnavailableException(e);
            }
            throw new ExecutionException(e);
        } catch (Exception e) {
            throw new ExecutionException(e);
        }
    }

    // Mirrors PersistentExecutorService's ExecutorUnavailableException split: treat capacity/
    // availability signals from the Kubernetes API as retryable, and genuine request/config
    // rejections (a bad manifest, RBAC denial) as a real failure that retrying will never fix.
    private boolean isRetryable(KubernetesClientException e) {
        int code = e.getCode();
        if (code <= 0) {
            // No HTTP response reached at all - apiserver unreachable, connection refused, timed out.
            return true;
        }
        if (code == 429 || code >= 500) {
            // Throttled, or a server-side error - both expected to clear on their own.
            return true;
        }
        if (code == 403) {
            // Admission control returns 403 both when a ResourceQuota is exhausted (transient -
            // frees up as other jobs finish) and on RBAC denial (a real config error). Only the
            // former is worth retrying.
            String message = e.getMessage();
            return message != null && message.contains("exceeded quota");
        }
        return false;
    }

    private String getConfigValue(ExecutorContext executorContext, String key, String deploymentDefault) {
        String value = executorContext.getEnvironmentVariables().get(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return deploymentDefault == null || deploymentDefault.isBlank() ? null : deploymentDefault;
    }

    private Map<String, String> parseKeyValueString(String input) {
        Map<String, String> result = new HashMap<>();

        if (input == null || input.trim().isEmpty()) {
            return result;
        }

        for (String pair : input.split(";")) {
            if (pair.trim().isEmpty()) {
                continue;
            }

            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                result.put(keyValue[0].trim(), keyValue[1].trim());
            } else if (keyValue.length == 1) {
                result.put(keyValue[0].trim(), "");
            }
        }

        return result;
    }
}
