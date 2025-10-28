package io.terrakube.api.plugin.state.model.logs;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LogsRequest {
    List<Log> data;
}