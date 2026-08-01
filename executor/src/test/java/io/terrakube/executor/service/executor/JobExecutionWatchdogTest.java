package io.terrakube.executor.service.executor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobExecutionWatchdogTest {

    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;

    @Test
    void doesNothingWhenNeverMarkedBusy() {
        JobExecutionWatchdog watchdog = new JobExecutionWatchdog(eventPublisher, 360);

        watchdog.checkForWedgedJob();

        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    void doesNothingWhileWithinTheAllowedDuration() {
        JobExecutionWatchdog watchdog = new JobExecutionWatchdog(eventPublisher, 360);

        watchdog.markBusy();
        watchdog.checkForWedgedJob();

        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    void marksThePodUnhealthyOnceBusyLongerThanTheConfiguredCeiling() throws InterruptedException {
        JobExecutionWatchdog watchdog = new JobExecutionWatchdog(eventPublisher, 0);

        watchdog.markBusy();
        Thread.sleep(5);
        watchdog.checkForWedgedJob();

        assertEquals(1, publishedEvents.size());
        assertEquals(LivenessState.BROKEN, ((AvailabilityChangeEvent<?>) publishedEvents.get(0)).getState());
    }

    @Test
    void markFreeResetsTheWatchdogSoAFinishedJobIsNeverFlaggedAsWedged() throws InterruptedException {
        JobExecutionWatchdog watchdog = new JobExecutionWatchdog(eventPublisher, 0);

        watchdog.markBusy();
        Thread.sleep(5);
        watchdog.markFree();
        watchdog.checkForWedgedJob();

        assertTrue(publishedEvents.isEmpty());
    }
}
