package io.terrakube.api.plugin.scheduler.module;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.terrakube.api.repository.ModuleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModuleRefreshPollerJobTest {

    @Mock
    ModuleRepository moduleRepository;
    @Mock
    ModuleRefreshTransactions moduleRefreshTransactions;
    @Mock
    ModuleRefreshWorker moduleRefreshWorker;
    @Mock
    ThreadPoolTaskExecutor moduleRefreshExecutor;
    @Mock
    ThreadPoolExecutor threadPoolExecutor;
    @Mock
    JobExecutionContext jobExecutionContext;

    ModuleRefreshProperties properties = new ModuleRefreshProperties();
    ModuleRefreshPollerJob subject;

    @BeforeEach
    void setUp() {
        subject = new ModuleRefreshPollerJob(moduleRepository, moduleRefreshTransactions, moduleRefreshWorker,
                moduleRefreshExecutor, properties);
        // One round per run unless a test asks for more.
        subject.runMillis = 0;
        subject.pauseMillis = 0;
    }

    // An executor of 2 threads and a queue of 3.
    private void executorBusy(int active, int queued) {
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(3);
        for (int i = 0; i < queued; i++) {
            queue.add(() -> {
            });
        }
        when(moduleRefreshExecutor.getMaxPoolSize()).thenReturn(2);
        when(moduleRefreshExecutor.getActiveCount()).thenReturn(active);
        when(moduleRefreshExecutor.getThreadPoolExecutor()).thenReturn(threadPoolExecutor);
        when(threadPoolExecutor.getQueue()).thenReturn(queue);
    }

    private void runTasksInline() {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(moduleRefreshExecutor).execute(any(Runnable.class));
    }

    @Test
    void claimsOnlyFreeSlots() throws Exception {
        executorBusy(2, 1);
        when(moduleRepository.findDueIds(any(), any())).thenReturn(List.of());

        subject.execute(jobExecutionContext);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(moduleRepository).findDueIds(any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void keepsClaimingWhileDueModulesFillEveryFreeSlot() throws Exception {
        subject.runMillis = 10_000;
        executorBusy(0, 0);
        List<UUID> firstRound = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        List<UUID> lastRound = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(moduleRepository.findDueIds(any(), any())).thenReturn(firstRound, lastRound);
        when(moduleRefreshTransactions.claim(any(), any(), any())).thenReturn(true);

        subject.execute(jobExecutionContext);

        verify(moduleRepository, times(2)).findDueIds(any(), any());
        verify(moduleRefreshExecutor, times(7)).execute(any(Runnable.class));
    }

    @Test
    void stopsAtTheDeadlineEvenWhenModulesRemainDue() throws Exception {
        executorBusy(0, 0);
        when(moduleRepository.findDueIds(any(), any())).thenAnswer(invocation -> List.of(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        when(moduleRefreshTransactions.claim(any(), any(), any())).thenReturn(true);

        subject.execute(jobExecutionContext);

        verify(moduleRepository, times(1)).findDueIds(any(), any());
    }

    @Test
    void fullExecutorClaimsNothing() throws Exception {
        executorBusy(2, 3);

        subject.execute(jobExecutionContext);

        verifyNoInteractions(moduleRepository, moduleRefreshTransactions);
    }

    @Test
    void queuesOneRefreshPerClaimedModuleUnderOneLease() throws Exception {
        executorBusy(0, 0);
        runTasksInline();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(moduleRepository.findDueIds(any(), any())).thenReturn(List.of(first, second));
        when(moduleRefreshTransactions.claim(any(), any(), any())).thenReturn(true);

        subject.execute(jobExecutionContext);

        ArgumentCaptor<Date> lease = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
        verify(moduleRefreshTransactions).claim(eq(first), lease.capture(), now.capture());
        verify(moduleRefreshExecutor, times(2)).execute(any(Runnable.class));
        verify(moduleRefreshWorker).refresh(first, lease.getValue());
        verify(moduleRefreshWorker).refresh(second, lease.getValue());
        assertThat(now.getValue().getTime() % 1000).isZero();
        assertThat(lease.getValue().getTime() - now.getValue().getTime()).isEqualTo(5 * 60_000L);
    }

    @Test
    void unclaimedRowsAreNotDispatched() throws Exception {
        executorBusy(0, 0);
        runTasksInline();
        UUID taken = UUID.randomUUID();
        UUID ours = UUID.randomUUID();
        when(moduleRepository.findDueIds(any(), any())).thenReturn(List.of(taken, ours));
        when(moduleRefreshTransactions.claim(eq(taken), any(), any())).thenReturn(false);
        when(moduleRefreshTransactions.claim(eq(ours), any(), any())).thenReturn(true);

        subject.execute(jobExecutionContext);

        verify(moduleRefreshWorker).refresh(eq(ours), any());
        verify(moduleRefreshWorker, never()).refresh(eq(taken), any());
    }

    @Test
    void rejectedRefreshReleasesItsLeaseAndEveryLaterOneAndStops() throws Exception {
        executorBusy(0, 0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(moduleRepository.findDueIds(any(), any())).thenReturn(List.of(first, second, third));
        when(moduleRefreshTransactions.claim(any(), any(), any())).thenReturn(true);
        doNothing().doThrow(new RejectedExecutionException("full")).when(moduleRefreshExecutor)
                .execute(any(Runnable.class));

        subject.execute(jobExecutionContext);

        ArgumentCaptor<Date> lease = ArgumentCaptor.forClass(Date.class);
        verify(moduleRefreshTransactions).claim(eq(first), lease.capture(), any());
        verify(moduleRefreshTransactions).releaseLeases(List.of(second, third), lease.getValue());
        verify(moduleRefreshExecutor, times(2)).execute(any(Runnable.class));
    }

    @Test
    void failureToRecordAResultDoesNotEscapeTheTask() throws Exception {
        executorBusy(0, 0);
        runTasksInline();
        UUID id = UUID.randomUUID();
        when(moduleRepository.findDueIds(any(), any())).thenReturn(List.of(id));
        when(moduleRefreshTransactions.claim(any(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("database down")).when(moduleRefreshWorker).refresh(eq(id), any());

        subject.execute(jobExecutionContext);

        verify(moduleRefreshWorker).refresh(eq(id), any());
    }
}
