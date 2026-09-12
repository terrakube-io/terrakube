package io.terrakube.api.plugin.state.model.runs;

import io.terrakube.api.plugin.state.model.generic.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class PolicyChecksModel {
    private List<Resource> data;
}
