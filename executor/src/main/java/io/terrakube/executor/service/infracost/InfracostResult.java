package io.terrakube.executor.service.infracost;

import lombok.Builder;
import lombok.Getter;

import java.io.File;
import java.util.Map;

@Getter
@Builder
public class InfracostResult {

    public enum Status {
        SUCCESS,
        ERROR,
        SKIPPED
    }

    private final Status status;
    private final Map<String, Object> normalizedContext;
    private final File infracostOpaDataFile;
    private final String errorMessage;
}
