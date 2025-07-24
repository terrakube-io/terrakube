package io.terrakube.api.plugin.vcs.provider.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GitlabDiffResponseModel {

    private String diff;

    @JsonProperty("new_path")
    private String newPath;

    @JsonProperty("old_path")
    private String oldPath;

    @JsonProperty("a_mode")
    private String aMode;

    @JsonProperty("b_mode")
    private String bMode;

    @JsonProperty("new_file")
    private boolean newFile;

    @JsonProperty("renamed_file")
    private boolean renamedFile;

    @JsonProperty("deleted_file")
    private boolean deletedFile;

    @JsonProperty("generated_file")
    private boolean generatedFile;
}


