package io.terrakube.api.plugin.scheduler.job.tcl.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
@Setter
public class Command {
    private String runtime;
    private String script;
    private int priority;
    private boolean before;
    private boolean after;
    private boolean verbose;
    private boolean beforeInit;
}
