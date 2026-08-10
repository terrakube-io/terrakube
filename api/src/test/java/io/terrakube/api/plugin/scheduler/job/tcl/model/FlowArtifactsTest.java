package io.terrakube.api.plugin.scheduler.job.tcl.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowArtifactsTest {

    @Test
    void artifactsRoundTrips() {
        Flow flow = new Flow();
        flow.setArtifacts(List.of("${ARTIFACT_PATHS}"));

        assertEquals(List.of("${ARTIFACT_PATHS}"), flow.getArtifacts());
    }
}
