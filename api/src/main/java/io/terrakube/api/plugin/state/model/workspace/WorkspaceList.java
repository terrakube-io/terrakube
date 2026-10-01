package io.terrakube.api.plugin.state.model.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class WorkspaceList {

    List<WorkspaceModel> data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    Map<String, Object> meta;
}
