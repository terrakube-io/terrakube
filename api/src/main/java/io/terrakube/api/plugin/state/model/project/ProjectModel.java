package io.terrakube.api.plugin.state.model.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.terrakube.api.plugin.state.model.generic.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@ToString
public class ProjectModel extends Resource {
    Map<String, Object> attributes;
    Relationships relationships;
}
