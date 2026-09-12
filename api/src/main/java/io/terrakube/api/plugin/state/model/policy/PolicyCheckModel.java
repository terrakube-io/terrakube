package io.terrakube.api.plugin.state.model.policy;

import io.terrakube.api.plugin.state.model.generic.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

@Getter
@Setter
@ToString
public class PolicyCheckModel extends Resource {

    private Map<String, Object> attributes;
    private Map<String, Object> relationships;
}
