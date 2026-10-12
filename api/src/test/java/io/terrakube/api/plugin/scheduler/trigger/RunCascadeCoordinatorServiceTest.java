package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.cascade.*;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Pure transition cases; transaction rollback, replay and concurrent joins are integration-tested. */
class RunCascadeCoordinatorServiceTest {
    RunCascadeRepository cascades = mock(RunCascadeRepository.class);
    RunCascadeNodeRepository nodes = mock(RunCascadeNodeRepository.class);
    RunCascadeEdgeRepository edges = mock(RunCascadeEdgeRepository.class);
    RunCascadeNodeAttemptRepository attempts = mock(RunCascadeNodeAttemptRepository.class);
    RunTriggerJobWriter writer = mock(RunTriggerJobWriter.class);
    TclService tcl = mock(TclService.class);
    RunCascadeCoordinatorService service;
    RunCascade cascade;
    List<RunCascadeNode> nodeList = new ArrayList<>();
    List<RunCascadeEdge> edgeList = new ArrayList<>();
    List<RunCascadeNodeAttempt> attemptList = new ArrayList<>();
    int jobId = 100;
    @BeforeEach void setup() {
        service = new RunCascadeCoordinatorService(cascades, nodes, edges, attempts,
            mock(WorkspaceRunTriggerRepository.class), new RunTriggerProperties(), writer,
            mock(JobNotificationTrigger.class), mock(ScheduleJobService.class), mock(RunCascadeMetrics.class),
            mock(RunCascadeNotificationTrigger.class), mock(JobRepository.class), mock(TemplateRepository.class), tcl);
        ReflectionTestUtils.setField(service, "entityManager", mock(jakarta.persistence.EntityManager.class));
        cascade = new RunCascade(); cascade.setId(UUID.randomUUID());
        when(cascades.lockForUpdate(cascade.getId())).thenReturn(Optional.of(cascade));
        when(cascades.save(any())).thenAnswer(i -> i.getArgument(0));
        when(nodes.save(any())).thenAnswer(i -> i.getArgument(0));
        when(edges.save(any())).thenAnswer(i -> i.getArgument(0));
        when(nodes.findByCascade_Id(any())).thenReturn(nodeList);
        when(edges.findByCascade_Id(any())).thenReturn(edgeList);
        when(edges.findById(any())).thenAnswer(i -> edgeList.stream().filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
        when(nodes.findById(any())).thenAnswer(i -> nodeList.stream().filter(n -> n.getId().equals(i.getArgument(0))).findFirst());
        when(nodes.findByCascade_IdAndWorkspace_Id(any(), any())).thenAnswer(i -> nodeList.stream().filter(n -> n.getWorkspace().getId().equals(i.getArgument(1))).findFirst());
        when(edges.findByCascade_IdAndDestinationWorkspace_Id(any(), any())).thenAnswer(i -> edgeList.stream().filter(e -> e.getDestinationWorkspace().getId().equals(i.getArgument(1))).toList());
        when(edges.findByCascade_IdAndSourceWorkspace_Id(any(), any())).thenAnswer(i -> edgeList.stream().filter(e -> e.getSourceWorkspace().getId().equals(i.getArgument(1))).toList());
        when(attempts.findByNode_IdOrderByAttemptNumberAsc(any())).thenAnswer(i -> attemptList.stream().filter(a -> a.getNode().getId().equals(i.getArgument(0))).sorted(Comparator.comparingInt(RunCascadeNodeAttempt::getAttemptNumber)).toList());
        when(attempts.findByJob_Id(anyInt())).thenAnswer(i -> attemptList.stream().filter(a -> a.getJob().getId() == (Integer)i.getArgument(0)).findFirst());
        when(attempts.save(any())).thenAnswer(i -> { RunCascadeNodeAttempt a=i.getArgument(0); if (!attemptList.contains(a)) attemptList.add(a); return a; });
        when(writer.persistInCascade(any(), any(), any(), anyInt(), any())).thenAnswer(i -> {
            Job job = new Job(); job.setId(jobId++); job.setWorkspace(i.getArgument(0));
            job.setTriggeredByJobId(((Job)i.getArgument(2)).getId()); job.setStatus(JobStatus.pending); return job;
        });
    }
    RunCascadeNode node() {
        Workspace workspace = new Workspace(); workspace.setId(UUID.randomUUID());
        RunCascadeNode node = new RunCascadeNode(); node.setId(UUID.randomUUID()); node.setCascade(cascade); node.setWorkspace(workspace); nodeList.add(node); return node;
    }
    WorkspaceRunTrigger edge(RunCascadeNode from, RunCascadeNode to, RunTriggerSynchronizationMode mode) {
        RunCascadeEdge edge = new RunCascadeEdge(); edge.setId(UUID.randomUUID()); edge.setCascade(cascade);
        edge.setSourceWorkspace(from.getWorkspace()); edge.setDestinationWorkspace(to.getWorkspace());
        edge.setSynchronizationMode(mode); edge.setOnDestroy(RunTriggerOnDestroyPolicy.TRIGGER);
        edge.setTemplateReference(UUID.randomUUID().toString()); edge.setTemplateTcl("frozen"); edgeList.add(edge);
        WorkspaceRunTrigger trigger = new WorkspaceRunTrigger(); trigger.setId(edge.getId()); return trigger;
    }
    Job parent(RunCascadeNode node, int id) { Job job = new Job(); job.setId(id); job.setWorkspace(node.getWorkspace()); return job; }
    @Test void allJoinsWaitAndReplayCannotCreateDuplicateAttempts() {
        var left=node(); var right=node(); var join=node();
        var a=edge(left,join,RunTriggerSynchronizationMode.ALL); var b=edge(right,join,RunTriggerSynchronizationMode.ALL);
        assertThat(service.enqueue(cascade,a,parent(left,1),1,false,true)).isNull();
        Job created=service.enqueue(cascade,b,parent(right,2),1,false,true);
        assertThat(created).isNotNull();
        assertThat(service.enqueue(cascade,b,parent(right,2),1,false,true)).isSameAs(created);
        assertThat(service.enqueue(cascade,a,parent(left,1),1,false,true)).isNull();
        assertThat(attemptList).hasSize(1);
    }
    @Test void anyJoinsRunOnFirstEligibleParentOnly() {
        var left=node(); var right=node(); var join=node();
        var a=edge(left,join,RunTriggerSynchronizationMode.ANY); var b=edge(right,join,RunTriggerSynchronizationMode.ANY);
        assertThat(service.enqueue(cascade,a,parent(left,1),1,false,true)).isNotNull();
        assertThat(service.enqueue(cascade,b,parent(right,2),1,false,true)).isNull();
        assertThat(attemptList).hasSize(1);
    }
    @Test void noChangeSkipsAnAllChainAndDoesNotCountAsFailure() {
        var a=node(); a.setStatus(RunCascadeNodeStatus.SUCCEEDED); var b=node(); var c=node();
        var ab=edge(a,b,RunTriggerSynchronizationMode.ALL); edge(b,c,RunTriggerSynchronizationMode.ALL);
        service.enqueue(cascade,ab,parent(a,1),1,false,false);
        assertThat(b.getStatus()).isEqualTo(RunCascadeNodeStatus.SKIPPED);
        assertThat(c.getStatus()).isEqualTo(RunCascadeNodeStatus.SKIPPED);
        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.COMPLETED);
    }
    @Test void allJoinAcceptsNoChangeParentsWhenAnotherParentChanged() {
        var left=node(); var right=node(); var join=node();
        var a=edge(left,join,RunTriggerSynchronizationMode.ALL); var b=edge(right,join,RunTriggerSynchronizationMode.ALL);
        assertThat(service.enqueue(cascade,a,parent(left,1),1,false,true)).isNull();
        assertThat(service.enqueue(cascade,b,parent(right,2),1,false,false)).isNotNull();
        assertThat(attemptList).hasSize(1);
    }
    @Test void missingWorkflowAlsoResolvesDescendants() {
        var a=node(); a.setStatus(RunCascadeNodeStatus.SUCCEEDED); var b=node(); var c=node();
        var ab=edge(a,b,RunTriggerSynchronizationMode.ALL); edge(b,c,RunTriggerSynchronizationMode.ALL);
        edgeList.getFirst().setTemplateReference(null);
        service.enqueue(cascade,ab,parent(a,1),1,false,true);
        assertThat(c.getStatus()).isEqualTo(RunCascadeNodeStatus.BLOCKED);
        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.BLOCKED);
    }

    @Test void ignoredDestroyStillAllowsAnotherAnyParent() {
        var a=node(); var b=node(); var join=node();
        var aj=edge(a,join,RunTriggerSynchronizationMode.ANY); var bj=edge(b,join,RunTriggerSynchronizationMode.ANY);
        edgeList.getFirst().setOnDestroy(RunTriggerOnDestroyPolicy.IGNORE);
        assertThat(service.enqueue(cascade,aj,parent(a,1),1,true,true)).isNull();
        assertThat(join.getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);
        assertThat(service.enqueue(cascade,bj,parent(b,2),1,false,true)).isNotNull();
    }
    @Test void cancellationPreventsEachDispatchAndIsIdempotent() {
        var a=node(); var b=node(); var ab=edge(a,b,RunTriggerSynchronizationMode.EACH);
        service.cancelCascade(cascade.getId()); service.cancelCascade(cascade.getId());
        assertThat(service.enqueue(cascade,ab,parent(a,1),1,false,true)).isNull();
        verifyNoInteractions(writer);
    }
    @Test void missingWorkflowPausesRatherThanStrandsAWorkspace() {
        var a=node(); var b=node(); var ab=edge(a,b,RunTriggerSynchronizationMode.ANY);
        edgeList.getFirst().setTemplateReference(null);
        service.enqueue(cascade,ab,parent(a,1),1,false,true);
        assertThat(b.getStatus()).isEqualTo(RunCascadeNodeStatus.BLOCKED);
        assertThat(b.getReason()).contains("No workflow");
    }
    @Test void destroyCannotDispatchAnApplyWorkflowAsPlanOnly() {
        var a=node(); var b=node(); var ab=edge(a,b,RunTriggerSynchronizationMode.ANY);
        var e=edgeList.getFirst(); e.setOnDestroy(RunTriggerOnDestroyPolicy.PLAN_ONLY);
        e.setPlanTemplateReference(UUID.randomUUID().toString()); e.setPlanTemplateTcl("apply");
        service.enqueue(cascade,ab,parent(a,1),1,true,true);
        assertThat(b.getStatus()).isEqualTo(RunCascadeNodeStatus.BLOCKED); verifyNoInteractions(writer);
    }
    @Test void everyUpstreamEventGetsAnAttemptButReplayDoesNot() {
        var a=node(); var b=node(); var ab=edge(a,b,RunTriggerSynchronizationMode.EACH);
        service.enqueue(cascade,ab,parent(a,1),1,false,true);
        service.enqueue(cascade,ab,parent(a,2),1,false,true);
        service.enqueue(cascade,ab,parent(a,2),1,false,true);
        assertThat(attemptList).hasSize(2);
    }
    @Test void lateCompletionCannotResolveAnotherActiveAttempt() {
        var a=node(); var b=node(); var ab=edge(a,b,RunTriggerSynchronizationMode.EACH);
        var first=service.enqueue(cascade,ab,parent(a,1),1,false,true);
        var second=service.enqueue(cascade,ab,parent(a,2),1,false,true);
        service.resolveNode(first);
        assertThat(b.getStatus()).isEqualTo(RunCascadeNodeStatus.RUNNING);
        service.resolveNodeAsUnsuccessful(second,RunCascadeNodeStatus.FAILED);
        service.resolveNode(first);
        assertThat(b.getStatus()).isEqualTo(RunCascadeNodeStatus.FAILED);
    }
}
