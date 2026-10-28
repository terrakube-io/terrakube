package io.terrakube.executor.service.mode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Ignores unknown fields: BatchModeServiceImpl reads it with a plain ObjectMapper, and a field a newer
// API adds must not fail an ephemeral executor.
@JsonIgnoreProperties(ignoreUnknown = true)
@ToString
@Getter
@Setter
public class TerraformJob {

    private List<Command> commandList;
    private List<Command> onFailureList;
    private String type;
    private String organizationId;
    private String workspaceId;
    private String jobId;
    private String stepId;
    // API clock (millis) when it dispatched this step; 0 from an API that does not send it.
    private long executionEpoch;
    private String terraformVersion;
    private String source;
    private String branch;
    private String folder;
    private String vcsType;
    @ToString.Exclude
    private String accessToken;
    private String connectionType;
    @ToString.Exclude
    private String terraformOutput;
    private boolean showHeader;
    private boolean refresh;
    private boolean refreshOnly;
    private boolean ignoreError;
    private boolean requireSavedPlan;
    @ToString.Exclude
    private String moduleSshKey;
    private String commitId;
    private boolean tofu;
    private String agentUrl;
    @ToString.Exclude
    private HashMap<String, String> environmentVariables;
    @ToString.Exclude
    private HashMap<String, String> variables;
    // Variables of a CLI run whose value is not a string, as HCL literals, written to a variable file so they keep their type.
    @ToString.Exclude
    private HashMap<String, String> hclVariables;
    private List<Map<String, Object>> liveChanges;
    private List<Map<String, Object>> jobDiagnostics;
    private List<PolicyContext> policyList;
    private List<PolicyExemptionContext> policyExemptionList;
    private Long deliveryRevision;
    // Globs of the files a plan step hands to its apply step (empty when the feature is off), and
    // whether the job has a later step that could use them.
    private List<String> artifactPatterns;
    private boolean multiStepJob;
    // Set by the plan step once it stored the bundle: where, and its sha256. Recorded on the job.
    private String artifactsUrl;
    private String artifactsChecksum;

}

