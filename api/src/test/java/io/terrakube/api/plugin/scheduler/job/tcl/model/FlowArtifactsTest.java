package io.terrakube.api.plugin.scheduler.job.tcl.model;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowArtifactsTest {

    private FlowConfig parse(String tcl) {
        return new Yaml(new Constructor(FlowConfig.class, new LoaderOptions())).load(tcl);
    }

    @Test
    void planStepArtifactsListIsReadFromTheTemplate() {
        FlowConfig config = parse("""
                flow:
                  - type: "terraformPlan"
                    step: 100
                    artifacts:
                      - "build/**"
                      - "${LAMBDA_DIR}/*.zip"
                  - type: "terraformApply"
                    step: 200
                """);

        assertEquals(java.util.List.of("build/**", "${LAMBDA_DIR}/*.zip"), config.getFlow().get(0).getArtifacts());
        assertNull(config.getFlow().get(1).getArtifacts());
    }

    @Test
    void aTemplateWithoutArtifactsIsUnchanged() {
        FlowConfig config = parse("""
                flow:
                  - type: "terraformPlan"
                    step: 100
                """);

        assertNull(config.getFlow().get(0).getArtifacts());
    }
}
