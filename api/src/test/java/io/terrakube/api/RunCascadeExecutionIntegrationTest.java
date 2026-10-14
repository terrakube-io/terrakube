package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.trigger.RunCascadeCoordinatorService;
import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventWriter;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.cascade.*;
import io.terrakube.api.rs.job.*;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real database tests for the execution invariants that mocks cannot establish. */
class RunCascadeExecutionIntegrationTest extends ServerApplicationTests {
    @Autowired RunCascadeCoordinatorService coordinator;
    @Autowired RunCascadeRepository cascades;
    @Autowired RunCascadeNodeRepository nodes;
    @Autowired RunCascadeEdgeRepository edges;
    @Autowired RunCascadeNodeAttemptRepository attempts;
    @Autowired WorkspaceRunTriggerRepository triggers;
    @Autowired RunTriggerEventWriter events;
    @Autowired RunTriggerEventRepository eventRows;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ScheduleJobService scheduleService;
    List<UUID> workspaceIds = new ArrayList<>();
    List<UUID> triggerIds = new ArrayList<>();
    List<UUID> cascadeIds = new ArrayList<>();
    List<Integer> jobIds = new ArrayList<>();
    Template template;

    @BeforeEach void prepare() {
        template = new Template(); template.setName("cascade-test"); template.setOrganization(organizationRepository.findAll().getFirst());
        template.setTcl(encoded("flow:\n  - type: terraformPlan\n    step: 100\n"));
        template = templateRepository.save(template);
    }
    @AfterEach void cleanup() {
        cascadeIds.forEach(cascades::deleteById);
        triggerIds.forEach(triggers::deleteById);
        for (UUID id : workspaceIds) {
            for (Job job : jobRepository.findAll()) {
                if (job.getWorkspace() != null && id.equals(job.getWorkspace().getId())) {
                    job.setDeleted(true); jobRepository.save(job);
                }
            }
            workspaceRepository.findById(id).ifPresent(ws -> { ws.setDeleted(true); workspaceRepository.save(ws); });
        }
        templateRepository.deleteById(template.getId());
    }
    String encoded(String yaml) { return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8)); }
    Workspace workspace() {
        Workspace ws = new Workspace(); ws.setName("cascade-test-"+UUID.randomUUID()); ws.setOrganization(template.getOrganization());
        ws.setDefaultTemplate(template.getId().toString()); ws.setBranch("main"); ws.setFolder("/"); ws.setSource("https://example.com/test");
        ws.setTerraformVersion("1.0.0"); ws=workspaceRepository.save(ws); workspaceIds.add(ws.getId()); return ws;
    }
    Job completed(Workspace ws) {
        Job job=new Job(); job.setWorkspace(ws); job.setOrganization(ws.getOrganization()); job.setStatus(JobStatus.completed);
        job.setCreatedBy("test"); job.setUpdatedBy("test"); job.setCreatedDate(new Date()); job.setUpdatedDate(new Date());
        job=jobRepository.save(job); jobIds.add(job.getId()); return job;
    }
    void dependency(Workspace source, Workspace destination, RunTriggerSynchronizationMode mode) {
        WorkspaceRunTrigger t=new WorkspaceRunTrigger(); t.setSourceWorkspace(source); t.setDestinationWorkspace(destination);
        t.setSynchronizationMode(mode); t.setTemplate(template); t=triggers.save(t); triggerIds.add(t.getId());
    }
    RunCascade start(Job origin) { RunCascade c=coordinator.ensureCascade(origin); cascadeIds.add(c.getId()); return c; }
    WorkspaceRunTrigger snapshot(RunCascade c, Workspace source, Workspace destination) {
        return coordinator.snapshotTriggers(c.getId(),source.getId()).stream()
                .filter(t -> t.getDestinationWorkspace().getId().equals(destination.getId())).findFirst().orElseThrow();
    }
    @Test void rollbackCannotLeaveAJobWithoutItsCascadeAttempt() {
        Workspace root=workspace(), target=workspace(); dependency(root,target,RunTriggerSynchronizationMode.ANY);
        Job parent=completed(root); RunCascade cascade=start(parent); var edge=snapshot(cascade,root,target);
        TransactionTemplate tx=new TransactionTemplate(transactions);
        Integer jobId=tx.execute(status -> { Job created=coordinator.enqueue(cascade,edge,parent,1,false,true); status.setRollbackOnly(); return created.getId(); });
        assertThat(jobRepository.findById(jobId)).isEmpty();
        var node=nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),target.getId()).orElseThrow();
        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);
        assertThat(attempts.findByNode_IdOrderByAttemptNumberAsc(node.getId())).isEmpty();
        Job recovered=coordinator.enqueue(cascade,edge,parent,1,false,true);
        assertThat(attempts.findByJob_Id(recovered.getId())).isPresent();
    }
    @Test void eventReplayReturnsTheSameCommittedJobEvenAfterCompletion() {
        Workspace root=workspace(), target=workspace(); dependency(root,target,RunTriggerSynchronizationMode.ALL);
        Job parent=completed(root); RunCascade cascade=start(parent); var edge=snapshot(cascade,root,target);
        Job first=coordinator.enqueue(cascade,edge,parent,1,false,true);
        coordinator.resolveNode(first);
        Job replay=coordinator.enqueue(cascade,edge,parent,1,false,true);
        assertThat(replay.getId()).isEqualTo(first.getId());
        var node=nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),target.getId()).orElseThrow();
        assertThat(attempts.findByNode_IdOrderByAttemptNumberAsc(node.getId())).hasSize(1);
    }
    @Test void configurationEditsAndDeletionCannotChangeAnActiveExecution() {
        Workspace root=workspace(), target=workspace(); dependency(root,target,RunTriggerSynchronizationMode.EACH);
        Job parent=completed(root); RunCascade cascade=start(parent); String original=template.getTcl();
        template.setTcl(encoded("flow:\n  - type: terraformDestroy\n    step: 100\n")); templateRepository.save(template);
        triggers.deleteById(triggerIds.removeFirst());
        Job created=coordinator.enqueue(cascade,snapshot(cascade,root,target),parent,1,false,true);
        assertThat(created.getTcl()).isEqualTo(original);
        assertThat(created.getTemplateReference()).isEqualTo(template.getId().toString());
        assertThat(coordinator.snapshotTriggers(cascade.getId(),root.getId())).hasSize(1);
        tclService.initJobConfiguration(created);
        assertThat(jobRepository.findById(created.getId()).orElseThrow().getTcl()).isEqualTo(original);
    }
    @Test void noChangeResolvesTheWholeChainAndLeafCompletionsGetEvents() {
        Workspace a=workspace(), b=workspace(), c=workspace(); dependency(a,b,RunTriggerSynchronizationMode.ALL); dependency(b,c,RunTriggerSynchronizationMode.ALL);
        Job parent=completed(a); RunCascade cascade=start(parent);
        coordinator.enqueue(cascade,snapshot(cascade,a,b),parent,1,false,false);
        assertThat(nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),c.getId()).orElseThrow().getStatus()).isEqualTo(RunCascadeNodeStatus.SKIPPED);
        assertThat(cascades.findById(cascade.getId()).orElseThrow().getStatus()).isEqualTo(RunCascadeStatus.COMPLETED);
    }
    @Test void cancelledCascadeRejectsEachDispatchAndOperatorRetry() {
        Workspace root=workspace(), target=workspace(); dependency(root,target,RunTriggerSynchronizationMode.EACH);
        Job parent=completed(root); RunCascade cascade=start(parent);
        var edge=snapshot(cascade,root,target); Job dispatched=coordinator.enqueue(cascade,edge,parent,1,false,true);
        coordinator.resolveNodeAsUnsuccessful(dispatched,RunCascadeNodeStatus.FAILED);
        coordinator.cancelCascade(cascade.getId());
        assertThat(coordinator.enqueue(cascade,edge,completed(root),1,false,true)).isNull();
        var node=nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),target.getId()).orElseThrow();
        assertThatThrownBy(() -> coordinator.retryNode(node.getId())).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentAnyParentsCreateOnlyOneAttempt() throws Exception {
        Workspace root=workspace(), left=workspace(), right=workspace(), join=workspace();
        dependency(root,left,RunTriggerSynchronizationMode.EACH); dependency(root,right,RunTriggerSynchronizationMode.EACH);
        dependency(left,join,RunTriggerSynchronizationMode.ANY); dependency(right,join,RunTriggerSynchronizationMode.ANY);
        Job origin=completed(root); RunCascade cascade=start(origin);
        Job a=coordinator.enqueue(cascade,snapshot(cascade,root,left),origin,1,false,true);
        Job b=coordinator.enqueue(cascade,snapshot(cascade,root,right),origin,1,false,true);
        var aj=snapshot(cascade,left,join); var bj=snapshot(cascade,right,join);
        CountDownLatch go=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Job> first=pool.submit(() -> { go.await(); return coordinator.enqueue(cascade,aj,a,2,false,true); });
            Future<Job> second=pool.submit(() -> { go.await(); return coordinator.enqueue(cascade,bj,b,2,false,true); });
            go.countDown(); Job x=first.get(10,TimeUnit.SECONDS), y=second.get(10,TimeUnit.SECONDS);
            assertThat((x==null ? 0 : 1)+(y==null ? 0 : 1)).isEqualTo(1);
            var node=nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),join.getId()).orElseThrow();
            assertThat(attempts.findByNode_IdOrderByAttemptNumberAsc(node.getId())).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void operatorSchedulingFailureLeavesARecoverableCommittedAttempt() throws Exception {
        Workspace root=workspace(), target=workspace(); dependency(root,target,RunTriggerSynchronizationMode.ALL);
        Job parent=completed(root); RunCascade cascade=start(parent);
        Job first=coordinator.enqueue(cascade,snapshot(cascade,root,target),parent,1,false,true);
        coordinator.resolveNodeAsUnsuccessful(first,RunCascadeNodeStatus.FAILED);
        doThrow(new RuntimeException("scheduler unavailable")).when(scheduleService).createJobContext(any());
        var node=nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),target.getId()).orElseThrow();
        Job retry=coordinator.retryNode(node.getId());
        assertThat(jobRepository.findById(retry.getId())).isPresent();
        assertThat(attempts.findByJob_Id(retry.getId())).isPresent();
        coordinator.resolveNode(retry);
        assertThat(nodes.findById(node.getId()).orElseThrow().getStatus()).isEqualTo(RunCascadeNodeStatus.SUCCEEDED);
    }
    @Test void leafAttemptGetsADurableCompletionEventWithoutLiveTriggers() {
        Workspace root=workspace(), leaf=workspace(); dependency(root,leaf,RunTriggerSynchronizationMode.ALL);
        Job parent=completed(root); RunCascade cascade=start(parent);
        Job first=coordinator.enqueue(cascade,snapshot(cascade,root,leaf),parent,1,false,true);
        events.enqueueIfQualifying(first);
        assertThat(eventRows.existsByJob_Id(first.getId())).isTrue();
    }
    @Test void allJoinsWaitForBothParentsAndDispatchOnce() {
        Workspace root=workspace(), left=workspace(), right=workspace(), join=workspace();
        dependency(root,left,RunTriggerSynchronizationMode.EACH); dependency(root,right,RunTriggerSynchronizationMode.EACH);
        dependency(left,join,RunTriggerSynchronizationMode.ALL); dependency(right,join,RunTriggerSynchronizationMode.ALL);
        Job origin=completed(root); RunCascade cascade=start(origin);
        Job a=coordinator.enqueue(cascade,snapshot(cascade,root,left),origin,1,false,true);
        Job b=coordinator.enqueue(cascade,snapshot(cascade,root,right),origin,1,false,true);
        assertThat(coordinator.enqueue(cascade,snapshot(cascade,left,join),a,2,false,true)).isNull();
        assertThat(coordinator.enqueue(cascade,snapshot(cascade,right,join),b,2,false,true)).isNotNull();
        assertThat(coordinator.enqueue(cascade,snapshot(cascade,left,join),a,2,false,true)).isNull();
    }

    @Test void concurrentOperatorRetriesCannotCreateDuplicateAttempts() throws Exception {
        Workspace root=workspace(), target=workspace(); dependency(root,target,RunTriggerSynchronizationMode.ALL);
        Job parent=completed(root); RunCascade cascade=start(parent);
        Job first=coordinator.enqueue(cascade,snapshot(cascade,root,target),parent,1,false,true);
        coordinator.resolveNodeAsUnsuccessful(first,RunCascadeNodeStatus.FAILED);
        var node=nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),target.getId()).orElseThrow();
        CountDownLatch go=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> retry=() -> { go.await(); try { coordinator.retryNode(node.getId()); return true; } catch (IllegalStateException expected) { return false; } };
        try {
            Future<Boolean> a=pool.submit(retry), b=pool.submit(retry); go.countDown();
            assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            assertThat(attempts.findByNode_IdOrderByAttemptNumberAsc(node.getId())).hasSize(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void noChangeAttemptCannotSkipDescendantsWhileAnotherEachInputCanStillArrive() {
        Workspace root=workspace(), left=workspace(), right=workspace(), each=workspace(), leaf=workspace();
        dependency(root,left,RunTriggerSynchronizationMode.EACH); dependency(root,right,RunTriggerSynchronizationMode.EACH);
        dependency(left,each,RunTriggerSynchronizationMode.EACH); dependency(right,each,RunTriggerSynchronizationMode.EACH);
        dependency(each,leaf,RunTriggerSynchronizationMode.ALL);
        Job origin=completed(root); RunCascade cascade=start(origin);
        Job a=coordinator.enqueue(cascade,snapshot(cascade,root,left),origin,1,false,true);
        Job b=coordinator.enqueue(cascade,snapshot(cascade,root,right),origin,1,false,true);
        Job first=coordinator.enqueue(cascade,snapshot(cascade,left,each),a,2,false,true);
        coordinator.resolveNode(first,false);
        assertThat(coordinator.enqueue(cascade,snapshot(cascade,each,leaf),first,3,false,false)).isNull();
        assertThat(nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),leaf.getId()).orElseThrow().getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);
        // The remaining upstream input resolves without a new job. That must finalize the
        // earlier no-change attempt's descendants, even though no new EACH completion arrives.
        coordinator.resolveNode(b, false);
        coordinator.enqueue(cascade,snapshot(cascade,right,each),b,2,false,false);
        assertThat(nodes.findByCascade_IdAndWorkspace_Id(cascade.getId(),leaf.getId()).orElseThrow().getStatus()).isEqualTo(RunCascadeNodeStatus.SKIPPED);
    }
}
