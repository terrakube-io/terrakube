package io.terrakube.api;

import io.terrakube.api.repository.RunCascadeNodeAttemptRepository;
import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.repository.RunCascadeRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeNode;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the cascade scenarios in {@code demo-data/cascade-scenarios.xml}, which people use to look at
 * cascades in a dev environment without running anything: they must load, and cover every status.
 */
public class CascadeSeedDataTest extends ServerApplicationTests {

    private static final String SEED_ORGANIZATION = "b0f1c2d3-1000-4000-8000-000000000001";

    @Autowired
    RunCascadeRepository cascades;

    @Autowired
    RunCascadeNodeRepository nodes;

    @Autowired
    RunCascadeNodeAttemptRepository attempts;

    private List<RunCascade> seeded() {
        return cascades.findAll().stream()
                .filter(c -> c.getOrganization().getId().toString().equals(SEED_ORGANIZATION))
                .toList();
    }

    @Test
    void seededCascadesCoverEveryOperatorFacingState() {
        assertThat(seeded()).extracting(RunCascade::getStatus)
                .containsExactlyInAnyOrder(RunCascadeStatus.COMPLETED, RunCascadeStatus.BLOCKED,
                        RunCascadeStatus.RUNNING, RunCascadeStatus.BLOCKED, RunCascadeStatus.DEGRADED,
                        RunCascadeStatus.CANCELLED);
    }

    @Test
    void everySeededCascadeHasItsOriginNodeAtDepthZero() {
        for (RunCascade cascade : seeded()) {
            List<RunCascadeNode> cascadeNodes = nodes.findByCascade_Id(cascade.getId());
            assertThat(cascadeNodes).anyMatch(n -> n.getDepth() == 0
                    && n.getStatus() == RunCascadeNodeStatus.SUCCEEDED
                    && n.getWorkspace().getId().equals(cascade.getOriginJob().getWorkspace().getId()));
        }
    }

    @Test
    void theRetryScenarioKeepsASupersededAttemptBesideTheLiveOne() {
        Map<String, List<RunCascadeNode>> byStatus = seeded().stream()
                .flatMap(c -> nodes.findByCascade_Id(c.getId()).stream())
                .collect(Collectors.groupingBy(n -> n.getStatus().name()));

        RunCascadeNode retried = byStatus.get("FAILED").stream()
                .filter(n -> attempts.findByNode_IdOrderByAttemptNumberAsc(n.getId()).size() == 2)
                .findFirst().orElseThrow();

        var history = attempts.findByNode_IdOrderByAttemptNumberAsc(retried.getId());
        assertThat(history.get(0).isSuperseded()).isTrue();
        assertThat(history.get(1).isSuperseded()).isFalse();
    }
}
