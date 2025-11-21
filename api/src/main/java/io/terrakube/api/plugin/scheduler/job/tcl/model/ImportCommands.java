package io.terrakube.api.plugin.scheduler.job.tcl.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

@ToString
@Getter
@Setter
public class ImportCommands {
    String repository;
    String folder;
    String branch;
    String vcsId;
    Map<String, String> inputsEnv;
    Map<String, String> inputsTerraform;
}
