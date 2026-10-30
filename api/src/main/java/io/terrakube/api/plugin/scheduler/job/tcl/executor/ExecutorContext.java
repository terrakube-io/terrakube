package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Command;

import java.util.HashMap;
import java.util.List;

@Builder(toBuilder = true)
@ToString
@Getter
@Setter
public class ExecutorContext {
    private List<Command> commandList;
    private List<Command> onFailureList;
    private List<String> artifactPatterns;
    private boolean multiStepJob;
    private String type;
    private String organizationId;
    private String workspaceId;
    private String jobId;
    private String stepId;
    // The API's System.currentTimeMillis() when it dispatched this step. The executor builds its
    // context revisions from it, so a re-dispatched step always outranks the execution it replaces.
    private long executionEpoch;
    private String terraformVersion;
    private String source;
    private String branch;
    private String folder;
    private String vcsType;
    private String connectionType;
    private boolean refresh;
    private boolean refreshOnly;
    private boolean showHeader;
    private boolean ignoreError;
    private boolean requireSavedPlan;
    private String accessToken;
    private String moduleSshKey;
    private String commitId;
    // The branch the checked-out commit must be on before this apply or destroy step runs, set when the workspace
    // restricts apply to its configured branch. Null otherwise, and for executors that do not read it.
    private String requireCommitOnBranch;
    private boolean tofu;
    @ToString.Exclude
    private HashMap<String, String> environmentVariables;
    @ToString.Exclude
    private HashMap<String, String> variables;
    // Variables of a CLI run whose value is not a string (a list, object, number or boolean), as HCL literals.
    // They are also in variables as --var text for executors that do not read this field. An executor that does
    // writes them to a variable file instead, which keeps their type for a variable without a type constraint.
    @ToString.Exclude
    private HashMap<String, String> hclVariables;
    private List<io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext> policyList;
    private List<io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyExemptionContext> policyExemptionList;
}

