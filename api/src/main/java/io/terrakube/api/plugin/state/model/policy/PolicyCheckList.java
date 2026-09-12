package io.terrakube.api.plugin.state.model.policy;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class PolicyCheckList {
    private List<PolicyCheckModel> data;
}
