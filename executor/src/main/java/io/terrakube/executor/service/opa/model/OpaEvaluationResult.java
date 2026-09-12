package io.terrakube.executor.service.opa.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Builder
@ToString
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OpaEvaluationResult {
    private String policySetId;
    private String policySetName;
    private String enforcementLevel;
    private String shadowEnforcementLevel;
    private String status;
    private int exitCode;
    private int passedRules;
    private int warningRules;
    private int softMandatoryViolations;
    private int hardMandatoryViolations;
    private int shadowHardViolations;
    private int shadowSoftViolations;
    @Builder.Default
    private List<PolicyViolation> violations = new ArrayList<>();
    @Builder.Default
    private List<PolicyViolation> exemptedViolations = new ArrayList<>();
    @Builder.Default
    private List<String> bufferedLogs = new ArrayList<>();
}
