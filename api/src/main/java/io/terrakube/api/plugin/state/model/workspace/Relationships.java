package io.terrakube.api.plugin.state.model.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.terrakube.api.plugin.state.model.workspace.state.consumers.RemoteStateConsumer;
import io.terrakube.api.plugin.state.model.workspace.tags.TagBindingList;
import io.terrakube.api.plugin.state.model.workspace.tags.TagDataList;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class Relationships {

    @JsonProperty("current-run")
    CurrentRunRelationship currentRun;

    @JsonProperty("project")
    ProjectRelationship project;

    @JsonProperty("remote-state-consumers")
    RemoteStateConsumer remoteStateConsumer;

    // Only read when creating a workspace: go-tfe sends list-style tags here
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("tags")
    TagDataList tags;

    // Only read when creating a workspace: go-tfe sends key/value tags here
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("tag-bindings")
    TagBindingList tagBindings;
}
