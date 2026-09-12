package io.terrakube.api.plugin.state.model.runs;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class Relationships {

    @JsonProperty("configuration-version")
    ConfigurationModel configurationVersion;
    WorkspaceModel workspace;
    PlanModel plan;
    ApplyModel apply;
    @JsonProperty("run-events")
    RunEventsModel runEventsModel;
    @JsonProperty("policy-checks")
    PolicyChecksModel policyChecks;
}
