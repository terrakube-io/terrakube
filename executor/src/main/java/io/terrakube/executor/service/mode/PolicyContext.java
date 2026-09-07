package io.terrakube.executor.service.mode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

@Builder
@ToString
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyContext {
    private String policyId;
    private String policyName;
    private String enforcementLevel;
    private String shadowEnforcementLevel;
    private String overrideTeam;
    private String vcsType;
    private String connectionType;
    private String accessToken;
    private String moduleSshKey;
    private String repository;
    private String branch;
    private String folder;
    private String opaVersion;
    private Map<String, String> inputs;
}
