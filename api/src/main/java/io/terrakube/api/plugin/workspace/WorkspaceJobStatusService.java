package io.terrakube.api.plugin.workspace;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;

/**
 * Keeps {@code workspace.lastJobStatus} as the status of the run that holds the workspace's queue.
 * <p>
 * A workspace runs one job at a time and newer jobs wait behind an unfinished one. The status of whichever job
 * changed last is not what the workspace is doing: a run that waits for approval is still what the workspace
 * waits for when a newer run is created behind it as {@code pending}. So the status shown is the one of the
 * oldest job that is not finished and, when every job is finished, the one of the job that changed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceJobStatusService {

    private final JobRepository jobRepository;
    private final WorkspaceRepository workspaceRepository;

    /** What the workspace shows: the status, and since when. */
    public record Shown(JobStatus status, Date date) {
    }

    /**
     * The status the workspace shows after {@code changed} was created or updated. The changed job is read from
     * memory (its status can be newer than its row), the other jobs from the database. {@code changed} has to
     * have an id already: it is compared with the ids of the other jobs, which is how their order is known.
     */
    public JobStatus statusFor(Job changed) {
        return shownFor(changed).status();
    }

    /**
     * Status and date the workspace shows after {@code changed} was created or updated.
     * <ul>
     *   <li>An older job that is not finished decides the status, and the date is the one of that job, so a run
     *   that has waited for approval for days does not look like it just changed because a newer run was
     *   queued behind it.</li>
     *   <li>When {@code changed} itself is the oldest unfinished job, or the latest finished one, the date is
     *   now.</li>
     *   <li>When every job is finished, the latest job decides, even if an older one is the one that was
     *   updated (a late comment or callback on an old run must not bring its failed status back).</li>
     * </ul>
     */
    public Shown shownFor(Job changed) {
        Date now = new Date(System.currentTimeMillis());
        Workspace workspace = changed.getWorkspace();
        if (workspace == null) {
            return new Shown(changed.getStatus(), now);
        }
        boolean changedHoldsTheQueue = changed.getStatus().holdsTheQueue();
        Optional<Job> olderUnfinished = jobRepository
                .findFirstByWorkspaceAndIdNotAndStatusInOrderByIdAsc(workspace, changed.getId(), JobStatus.holdingTheQueue())
                .filter(other -> !changedHoldsTheQueue || other.getId() < changed.getId());
        if (olderUnfinished.isPresent()) {
            return shownBy(olderUnfinished.get(), now);
        }
        if (changedHoldsTheQueue) {
            return new Shown(changed.getStatus(), now);
        }
        // Every job is finished. A newer job than the one that changed is what the workspace last ran.
        Optional<Job> newer = jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace)
                .filter(latest -> latest.getId() > changed.getId());
        return newer.map(latest -> shownBy(latest, now)).orElseGet(() -> new Shown(changed.getStatus(), now));
    }

    private static Shown shownBy(Job job, Date fallbackDate) {
        return new Shown(job.getStatus(), job.getUpdatedDate() != null ? job.getUpdatedDate() : fallbackDate);
    }

    /** Sets status and date on the workspace of {@code changed} and saves it. */
    public void refresh(Job changed) {
        Workspace workspace = changed.getWorkspace();
        if (workspace == null) {
            return;
        }
        Shown shown = shownFor(changed);
        log.info("Updating last status for workspace {} to {} (job {} is {})", workspace.getName(), shown.status(),
                changed.getId(), changed.getStatus());
        workspace.setLastJobStatus(shown.status());
        workspace.setLastJobDate(shown.date());
        workspaceRepository.save(workspace);
    }
}
