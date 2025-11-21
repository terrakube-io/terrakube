package io.terrakube.api.plugin.scheduler.job.tcl.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@ToString
@Getter
@Setter
public class Flow {
    private String type;
    private String team;
    private String name;
    private boolean ignoreError = false;
    private String error;
    private int step;
    List<Command> commands;

    List<ScheduleTemplate> templates;

    Map<String, String> inputsEnv;

    Map<String, String> inputsTerraform;

    ImportCommands importCommands;
}
