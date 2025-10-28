package io.terrakube.api.plugin.state.model.logs;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
public class Log {
    private Integer jobId;
    private String stepId;
    private Integer lineNumber;
    private String output;

    public Map<String, String> toStrMap() {
        Map<String, String> map = new LinkedHashMap();
        map.put("jobId", String.valueOf(getJobId()));
        map.put("stepId", getStepId());
        map.put("lineNumber", String.valueOf(getLineNumber()));
        map.put("output", getOutput());
        return map;
    }
}