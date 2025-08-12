package io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
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
    private static final String EPHEMERAL_CPU_LIMIT = "EPHEMERAL_CPU_LIMIT";
    private static final String EPHEMERAL_MEMORY_LIMIT = "EPHEMERAL_MEMORY_LIMIT";

    KubernetesClient kubernetesClient;
    EphemeralConfiguration ephemeralConfiguration;

    public ExecutorContext sendToEphemeralExecutor(Job job, ExecutorContext executorContext) {
        final String jobName = "job-" + job.getId();
        deleteEphemeralJob(job);
        log.info("Ephemeral Executor Image {}, Job: {}, Namespace: {}, NodeSelector: {}", ephemeralConfiguration.getImage(), jobName, ephemeralConfiguration.getNamespace(), ephemeralConfiguration.getNodeSelector());
        SecretEnvSource secretEnvSource = new SecretEnvSource();
        secretEnvSource.setName(ephemeralConfiguration.getSecret());
        EnvFromSource envFromSource = new EnvFromSource();
        envFromSource.setSecretRef(secretEnvSource);
        final List<EnvFromSource> executorEnvVarFromSecret = Arrays.asList(envFromSource);

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

        final List<EnvVar> executorEnvVarFlags = Arrays.asList(executorFlagBatch, executorFlagBatchJsonContent);

        Optional<String> nodeSelector = Optional.ofNullable(executorContext.getEnvironmentVariables().getOrDefault(NODE_SELECTOR, null));
        Map<String, String> nodeSelectorInfo = new HashMap();
        log.info("Custom Node selector: {} {}", nodeSelector.isPresent(), nodeSelector.isEmpty());
        if(nodeSelector.isPresent()) {
            log.info("Using custom node selector information");
            for (String nodeSelectorData : nodeSelector.get().split(";")) {
                String[] info = nodeSelectorData.split("=");
                nodeSelectorInfo.put(info[0], info[1]);
            }
        } else {
            log.info("Using default node selector information");
            nodeSelectorInfo = ephemeralConfiguration.getNodeSelector();
        }

        Optional<String> tolerationsInfo = Optional.ofNullable(
                executorContext.getEnvironmentVariables().getOrDefault(TOLERATIONS, null));
        List<Toleration> tolerations = new ArrayList<>();

        if (tolerationsInfo.isPresent()) {
            for (String tolerationData : tolerationsInfo.get().split(";")) {
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

        Optional<String> annotationsInfo = Optional.ofNullable(executorContext.getEnvironmentVariables().getOrDefault(ANNOTATIONS, null));
        Map<String, String> annotations = new HashMap();
        log.info("Custom Annotations: {}", annotationsInfo.isPresent());
        if(annotationsInfo.isPresent()) {
            for (String annotationData : annotationsInfo.get().split(";")) {
                String[] info = annotationData.split("=");
                annotations.put(info[0], info[1]);
            }
        }

        Optional<String> serviceAccountInfo = Optional.ofNullable(
                executorContext.getEnvironmentVariables().getOrDefault(SERVICE_ACCOUNT, null));
        String serviceAccount = serviceAccountInfo.orElse(null);

        // Volume and VolumeMount for ConfigMap if specified
        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> volumeMounts = new ArrayList<>();

        Optional<String> configMapNameOpt = Optional.ofNullable(executorContext.getEnvironmentVariables().get(CONFIG_MAP_NAME));
        Optional<String> configMapMountPathOpt = Optional.ofNullable(executorContext.getEnvironmentVariables().get(CONFIG_MAP_PATH));
        if (configMapNameOpt.isPresent()) {
            String configMapName = configMapNameOpt.get();
            String mountPath = configMapMountPathOpt.orElse("/tmp");  // Default mount path if not specified

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

        Optional<String> configPodSecurityContext = Optional
                .ofNullable(executorContext.getEnvironmentVariables().getOrDefault(POD_SECURITY_CONTEXT, null));
        PodSecurityContext podSecurityContext = new PodSecurityContextBuilder().withFsGroup(1000L).build();
        if (configPodSecurityContext.isPresent()) {
            log.info("Using custom pod security context");
            String[] podSecurityContextData = configPodSecurityContext.get().split(";");
            for (String data : podSecurityContextData) {
                String[] securitySetting = data.split("=");
                switch (securitySetting[0]) {
                    case "fsGroup":
                        podSecurityContext.setFsGroup(Long.parseLong(securitySetting[1]));
                        break;
                    case "runAsNonRoot":
                        podSecurityContext.setRunAsNonRoot(Boolean.parseBoolean(securitySetting[1]));
                        break;
                    case "runAsUser":
                        podSecurityContext.setRunAsUser(Long.parseLong(securitySetting[1]));
                        break;
                }
            }
        }

        Optional<String> configSecurityContext = Optional
                .ofNullable(executorContext.getEnvironmentVariables().getOrDefault(SECURITY_CONTEXT, null));
        SecurityContext securityContext = new SecurityContext();
        if (configSecurityContext.isPresent()) {
            log.info("Using custom security context");
            String[] securityContextData = configSecurityContext.get().split(";");
            for (String data : securityContextData) {
                String[] securitySetting = data.split("=");
                switch (securitySetting[0]) {
                    case "allowPrivilegeEscalation":
                        securityContext.setAllowPrivilegeEscalation(Boolean.parseBoolean(securitySetting[1]));
                        break;
                }
            }
        }

        Optional<String> cpuRequestOpt = Optional.ofNullable(executorContext.getEnvironmentVariables().get(EPHEMERAL_CPU_REQUEST));
        Optional<String> memoryRequestOpt = Optional.ofNullable(executorContext.getEnvironmentVariables().get(EPHEMERAL_MEMORY_REQUEST));
        Optional<String> cpuLimitOpt = Optional.ofNullable(executorContext.getEnvironmentVariables().get(EPHEMERAL_CPU_LIMIT));
        Optional<String> memoryLimitOpt = Optional.ofNullable(executorContext.getEnvironmentVariables().get(EPHEMERAL_MEMORY_LIMIT));

        ResourceRequirementsBuilder resourceBuilder = new ResourceRequirementsBuilder();
        boolean hasResources = false;

        if (cpuRequestOpt.isPresent()) {
            resourceBuilder.addToRequests("cpu", new Quantity(cpuRequestOpt.get()));
            hasResources = true;
        }
        if (memoryRequestOpt.isPresent()) {
            resourceBuilder.addToRequests("memory", new Quantity(memoryRequestOpt.get()));
            hasResources = true;
        }
        if (cpuLimitOpt.isPresent()) {
            resourceBuilder.addToLimits("cpu", new Quantity(cpuLimitOpt.get()));
            hasResources = true;
        }
        if (memoryLimitOpt.isPresent()) {
            resourceBuilder.addToLimits("memory", new Quantity(memoryLimitOpt.get()));
            hasResources = true;
        }

        io.fabric8.kubernetes.api.model.batch.v1.Job k8sJob = new JobBuilder()
                .withApiVersion("batch/v1")
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(ephemeralConfiguration.getNamespace())
                .withLabels(Collections.singletonMap("jobId", executorContext.getJobId()))
                .withLabels(Collections.singletonMap("organizationId", executorContext.getOrganizationId()))
                .withLabels(Collections.singletonMap("workspaceId", executorContext.getWorkspaceId()))
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
                .withName(jobName)
                .withEnvFrom(executorEnvVarFromSecret)
                .withImage(ephemeralConfiguration.getImage())
                .withEnv(executorEnvVarFlags)
                .withVolumeMounts(volumeMounts)
                .withSecurityContext(securityContext)
                .withResources(hasResources ? resourceBuilder.build() : null)
                .endContainer()
                .withRestartPolicy("Never")
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        log.info("Running ephemeral job");
        try {
            kubernetesClient.batch().v1().jobs().inNamespace(ephemeralConfiguration.getNamespace()).createOrReplace(k8sJob);
            return executorContext;
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
        return null;
    }

    public void deleteEphemeralJob(Job job){
        try {
            io.fabric8.kubernetes.api.model.batch.v1.Job jobK8s = kubernetesClient
                    .batch()
                    .jobs()
                    .inNamespace(ephemeralConfiguration.getNamespace())
                    .withName("job-" + job.getId())
                    .get();

            if (jobK8s != null) {
                kubernetesClient
                        .batch()
                        .jobs()
                        .inNamespace(ephemeralConfiguration.getNamespace())
                        .withName("job-" + job.getId())
                        .delete();
            }

        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }
}
