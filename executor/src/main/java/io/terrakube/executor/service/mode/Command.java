package io.terrakube.executor.service.mode;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import io.terrakube.executor.service.scripts.ScriptType;

@ToString
@Getter
@Setter
public class Command {
    private ScriptType runtime;
    private String script;
    private int priority;
    private boolean before;
    private boolean after;
    private boolean verbose;
    private boolean beforeInit;
}
