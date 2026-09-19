package io.terrakube.api.plugin.state.model.workspace.tags;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class TagBindingList {
    List<TagBindingModel> data;
}
