package io.terrakube.api.plugin.state.model.project;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProjectList {
    List<ProjectModel> data;
}
