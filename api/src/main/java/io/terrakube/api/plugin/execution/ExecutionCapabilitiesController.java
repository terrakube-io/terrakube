package io.terrakube.api.plugin.execution;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class ExecutionCapabilitiesController {
    @GetMapping(value = "/execution/v1/capabilities", produces = "application/json")
    public Map<String, Boolean> capabilities() {
        return Map.of("jobVariables", true);
    }
}
