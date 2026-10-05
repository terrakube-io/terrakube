package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.trigger.CyclicDependencyException;
import io.terrakube.api.plugin.scheduler.trigger.FanOutLimitExceededException;
import io.terrakube.api.plugin.scheduler.trigger.RunTriggerProperties;
import io.terrakube.api.plugin.scheduler.trigger.WorkspaceGraphValidationService;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository.TriggerEdge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cycle detection and the outbound fan-out limit over the run trigger graph. Uses ids only, so
 * no database is needed.
 */
class WorkspaceGraphValidationServiceTests {

    private WorkspaceRunTriggerRepository repository;
    private WorkspaceRepository workspaceRepository;
    private RunTriggerProperties properties;
    private WorkspaceGraphValidationService service;

    private final UUID org = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(WorkspaceRunTriggerRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.lockForUpdate(any())).thenReturn(Optional.empty());
        properties = new RunTriggerProperties();
        service = new WorkspaceGraphValidationService(repository, workspaceRepository, properties);
    }

    private TriggerEdge edge(UUID id, UUID source, UUID destination) {
        TriggerEdge e = mock(TriggerEdge.class);
        when(e.getId()).thenReturn(id);
        when(e.getSourceId()).thenReturn(source);
        when(e.getDestinationId()).thenReturn(destination);
        return e;
    }

    private void graph(TriggerEdge... edges) {
        when(repository.findEdgesByOrganizationId(any())).thenReturn(List.of(edges));
    }

    @Test
    void acceptsAnEdgeIntoAnEmptyGraph() {
        graph();
        assertDoesNotThrow(() -> service.validateAcyclic(org, null, a, b));
    }

    /** a -> b already exists; adding b -> c just extends the chain. */
    @Test
    void acceptsAChainExtension() {
        graph(edge(UUID.randomUUID(), a, b));
        assertDoesNotThrow(() -> service.validateAcyclic(org, null, b, c));
    }

    /** Two workspaces both feeding a third is a diamond, not a cycle. */
    @Test
    void acceptsFanInWhichIsNotACycle() {
        graph(edge(UUID.randomUUID(), a, c), edge(UUID.randomUUID(), b, c));
        assertDoesNotThrow(() -> service.validateAcyclic(org, null, a, b));
    }

    @Test
    void rejectsADirectCycle() {
        graph(edge(UUID.randomUUID(), a, b));
        CyclicDependencyException thrown = assertThrows(CyclicDependencyException.class,
                () -> service.validateAcyclic(org, null, b, a));
        assertTrue(thrown.getMessage().contains("circular dependency"), thrown.getMessage());
    }

    /** a -> b -> c, so c -> a closes a three-hop loop. */
    @Test
    void rejectsAnIndirectCycle() {
        graph(edge(UUID.randomUUID(), a, b), edge(UUID.randomUUID(), b, c));
        assertThrows(CyclicDependencyException.class,
                () -> service.validateAcyclic(org, null, c, a));
    }

    @Test
    void rejectsALongerCycle() {
        graph(edge(UUID.randomUUID(), a, b), edge(UUID.randomUUID(), b, c), edge(UUID.randomUUID(), c, d));
        assertThrows(CyclicDependencyException.class,
                () -> service.validateAcyclic(org, null, d, a));
    }

    @Test
    void rejectsASelfTrigger() {
        graph();
        assertThrows(CyclicDependencyException.class,
                () -> service.validateAcyclic(org, null, a, a));
    }

    /**
     * On update the row is already flushed, so the edge under validation must be excluded -
     * otherwise re-saving an existing a -> b would look like a cycle against itself.
     */
    @Test
    void excludesTheEdgeBeingUpdated() {
        UUID triggerId = UUID.randomUUID();
        graph(edge(triggerId, a, b));
        assertDoesNotThrow(() -> service.validateAcyclic(org, triggerId, a, b));
    }

    /**
     * A cycle that already exists elsewhere must not hang the search for an unrelated edge.
     * The graph should never contain one, but the traversal has to terminate regardless.
     */
    @Test
    void terminatesWhenTheGraphAlreadyContainsACycle() {
        graph(edge(UUID.randomUUID(), a, b), edge(UUID.randomUUID(), b, a));
        assertDoesNotThrow(() -> service.validateAcyclic(org, null, c, d));
    }

    /** A deep chain must not overflow the stack: the search is iterative. */
    @Test
    void handlesADeepChainWithoutStackOverflow() {
        int depth = 10_000;
        UUID[] nodes = new UUID[depth + 1];
        for (int i = 0; i <= depth; i++) {
            nodes[i] = UUID.randomUUID();
        }
        TriggerEdge[] edges = new TriggerEdge[depth];
        for (int i = 0; i < depth; i++) {
            edges[i] = edge(UUID.randomUUID(), nodes[i], nodes[i + 1]);
        }
        graph(edges);

        // last -> first closes the chain into a loop and must be caught
        assertThrows(CyclicDependencyException.class,
                () -> service.validateAcyclic(org, null, nodes[depth], nodes[0]));
    }

    @Test
    void ignoresIncompleteInput() {
        graph();
        assertDoesNotThrow(() -> service.validateAcyclic(null, null, a, b));
        assertDoesNotThrow(() -> service.validateAcyclic(org, null, null, b));
        assertDoesNotThrow(() -> service.validateAcyclic(org, null, a, null));
    }

    // ---------------------------------------------------------------- fan-out limit

    private void enabledCount(UUID source, long count) {
        when(repository.countBySourceWorkspaceIdAndEnabledTrueAndDestinationWorkspace_DeletedFalse(source))
                .thenReturn(count);
    }

    @Test
    void acceptsAnEnabledEdgeUnderTheLimit() {
        properties.setMaxOutboundTriggersPerWorkspace(5);
        enabledCount(a, 3);
        assertDoesNotThrow(() -> service.validateFanOutLimit(a, true));
    }

    /** The edge that fills the limit, not one past it, must be accepted. */
    @Test
    void acceptsAnEnabledEdgeExactlyAtTheLimit() {
        properties.setMaxOutboundTriggersPerWorkspace(5);
        enabledCount(a, 5);
        assertDoesNotThrow(() -> service.validateFanOutLimit(a, true));
    }

    @Test
    void rejectsAnEnabledEdgeOneOverTheLimit() {
        properties.setMaxOutboundTriggersPerWorkspace(5);
        enabledCount(a, 6);
        FanOutLimitExceededException thrown = assertThrows(FanOutLimitExceededException.class,
                () -> service.validateFanOutLimit(a, true));
        assertTrue(thrown.getMessage().contains("6"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("5"), thrown.getMessage());
    }

    /** A disabled edge never contributes to dispatch, so it is never limited. */
    @Test
    void neverLimitsADisabledEdgeEvenOverTheCount() {
        properties.setMaxOutboundTriggersPerWorkspace(5);
        enabledCount(a, 50);
        assertDoesNotThrow(() -> service.validateFanOutLimit(a, false));
    }

    @Test
    void ignoresAMissingSourceForTheFanOutCheck() {
        assertDoesNotThrow(() -> service.validateFanOutLimit(null, true));
    }

    /** 0 or negative is "no cap", not "reject every creation". */
    @Test
    void zeroOrNegativeLimitDisablesTheFanOutCheck() {
        properties.setMaxOutboundTriggersPerWorkspace(0);
        enabledCount(a, 1_000);
        assertDoesNotThrow(() -> service.validateFanOutLimit(a, true));

        properties.setMaxOutboundTriggersPerWorkspace(-1);
        assertDoesNotThrow(() -> service.validateFanOutLimit(a, true));
    }

    /** Locks the source row before counting, so two concurrent creations can't both pass. */
    @Test
    void locksTheSourceWorkspaceBeforeCounting() {
        properties.setMaxOutboundTriggersPerWorkspace(5);
        enabledCount(a, 3);
        service.validateFanOutLimit(a, true);
        verify(workspaceRepository).lockForUpdate(a);
    }

    @Test
    void neverLocksForADisabledOrMissingSource() {
        service.validateFanOutLimit(null, true);
        service.validateFanOutLimit(a, false);
        verify(workspaceRepository, never()).lockForUpdate(any());
    }
}
