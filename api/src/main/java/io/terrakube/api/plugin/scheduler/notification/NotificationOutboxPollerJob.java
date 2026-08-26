package io.terrakube.api.plugin.scheduler.notification;

import java.util.Date;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.plugin.notification.NotificationOutboxTransactions;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@DisallowConcurrentExecution
public class NotificationOutboxPollerJob implements Job {

    private static final int MAX_ATTEMPTS = 3;

    private final NotificationOutboxRepository notificationOutboxRepository;
    private final NotificationDispatchService notificationDispatchService;
    private final NotificationOutboxTransactions notificationOutboxTransactions;
    private final int batchSize;
    private final long stuckSendingThresholdMillis;

    public NotificationOutboxPollerJob(NotificationOutboxRepository notificationOutboxRepository,
            NotificationDispatchService notificationDispatchService,
            NotificationOutboxTransactions notificationOutboxTransactions,
            @Value("${io.terrakube.notification.poller.batchSize:200}") int batchSize,
            @Value("${io.terrakube.notification.poller.stuckSendingThresholdMinutes:5}") long stuckSendingThresholdMinutes) {
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.notificationDispatchService = notificationDispatchService;
        this.notificationOutboxTransactions = notificationOutboxTransactions;
        this.batchSize = batchSize;
        this.stuckSendingThresholdMillis = stuckSendingThresholdMinutes * 60_000L;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // Quartz's SchedulerFactoryBean instantiates and field-autowires Job instances directly
        // rather than looking them up from the ApplicationContext, so this class never gets an
        // AOP proxy of its own - any @Transactional declared directly here would silently never
        // apply. Both DB-mutating steps below are therefore delegated to
        // NotificationOutboxTransactions, a real Spring-managed bean whose own proxy the calls
        // below go through.
        notificationOutboxTransactions.sweepStuckSendingRows(
                new Date(System.currentTimeMillis() - stuckSendingThresholdMillis), MAX_ATTEMPTS);
        dispatchDueRows();
    }

    private void dispatchDueRows() {
        List<NotificationOutbox> due = notificationOutboxRepository.findDueForDispatch(NotificationOutboxStatus.PENDING,
                new Date(), PageRequest.of(0, batchSize));

        int dispatched = 0;
        for (NotificationOutbox outbox : due) {
            try {
                notificationDispatchService.dispatchAsync(outbox.getId());
                dispatched++;
            } catch (RejectedExecutionException e) {
                // The dispatch executor's queue is full - this row is still PENDING (this
                // submission never claimed it) and will be picked up again on the next tick.
                log.warn("Notification dispatch executor rejected outbox {}, will retry next poll cycle",
                        outbox.getId());
            }
        }
        if (dispatched > 0) {
            log.info("Notification outbox poller dispatched {} pending row(s)", dispatched);
        }
    }
}
