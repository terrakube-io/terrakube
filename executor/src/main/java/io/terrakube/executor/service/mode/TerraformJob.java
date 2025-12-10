package io.terrakube.executor.service.mode;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@ToString
@Getter
@Setter
public class TerraformJob {

    private List<Command> commandList;
    private String type;
    private String organizationId;
    private String workspaceId;
    private String jobId;
    private String stepId;
    private String terraformVersion;
    private String source;
    private String branch;
    private String folder;
    private String vcsType;
    private String accessToken;
    private String connectionType;
    private String terraformOutput;
    private String rawState;
    private boolean showHeader;
    private boolean refresh;
    private boolean refreshOnly;
    private boolean ignoreError;
    private String moduleSshKey;
    private String commitId;
    private boolean tofu;
    private String agentUrl;
    private HashMap<String, String> environmentVariables;
    private List<TerraformVariable> variables = new ArrayList<>();

}
