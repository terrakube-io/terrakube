package io.terrakube.api.plugin.vcs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PrCommentService {

    private static final int MAX_COMMENT_LENGTH = 60000;
    private static final int MAX_TABLE_ROWS = 50;
    private static final Set<VcsType> PR_COMMENT_SUPPORTED_VCS = EnumSet.of(VcsType.GITHUB, VcsType.GITLAB, VcsType.BITBUCKET);
    private static final Pattern RUN_SUMMARY_PATTERN = Pattern.compile(
            // The "N to import, " clause only appears when the plan includes an import block -
            // optional, and always first when present (matches Terraform/OpenTofu's own
            // "Plan: N to import, N to add, N to change, N to destroy." message ordering).
            "(Plan: (?:\\d+ to import, )?\\d+ to add, \\d+ to change, \\d+ to destroy\\."
            + "|No changes\\. Your infrastructure matches the configuration\\."
            + "|Apply complete! Resources: \\d+ added, \\d+ changed, \\d+ destroyed\\.)");
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "[\\u001b\\u009b][\\[()#;?]*(?:\\d{1,4}(?:;\\d{1,4})*)?[0-9A-ORZcf-nq-uy=><~]");

    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    BitBucketWebhookService bitBucketWebhookService;
    JobRepository jobRepository;
    StepRepository stepRepository;
    StorageTypeService storageTypeService;
    StreamingService streamingService;
    ObjectMapper objectMapper;

    @Value("${io.terrakube.ui.url:}")
    String uiUrl;

    public PrCommentService(GitHubWebhookService gitHubWebhookService, GitLabWebhookService gitLabWebhookService,
            BitBucketWebhookService bitBucketWebhookService, JobRepository jobRepository, StepRepository stepRepository,
            StorageTypeService storageTypeService, StreamingService streamingService, ObjectMapper objectMapper) {
        this.gitHubWebhookService = gitHubWebhookService;
        this.gitLabWebhookService = gitLabWebhookService;
        this.bitBucketWebhookService = bitBucketWebhookService;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.storageTypeService = storageTypeService;
        this.streamingService = streamingService;
        this.objectMapper = objectMapper;
    }

    public void postPlanResult(Job job) {
        if (job.getPrNumber() == null || job.getPrNumber() == 0) return;

        String planOutput = fetchStepOutputText(job);
        String markdownComment = formatPlanComment(job, planOutput);

        String existingThreadCommentId = findReusablePlanCommentId(job);
        String commentId = existingThreadCommentId != null
                ? attemptUpdateComment(job, existingThreadCommentId, markdownComment)
                : attemptPostComment(job, markdownComment);
        if (commentId != null) {
            job.setPrCommentId(commentId);
        }
        jobRepository.save(job);
    }

    /**
     * Reuses the comment from the most recent prior plan job on this same PR (if any) so
     * repeated replans update one running comment instead of stacking a new one per push.
     * Apply jobs are excluded so the apply audit trail is never overwritten in place.
     */
    private String findReusablePlanCommentId(Job job) {
        return jobRepository.findFirstByWorkspaceAndPrNumberAndIdNotAndAutoApplyFalseAndPrCommentIdIsNotNullOrderByIdDesc(
                        job.getWorkspace(), job.getPrNumber(), job.getId())
                .map(Job::getPrCommentId)
                .orElse(null);
    }

    public void postApplyResult(Job job) {
        if (job.getPrNumber() == null || job.getPrNumber() == 0) return;

        String output = fetchStepOutputText(job);
        String markdownComment = formatApplyComment(job, output);
        attemptPostComment(job, markdownComment);
        jobRepository.save(job);
    }

    private String attemptPostComment(Job job, String markdownComment) {
        VcsType vcsType = job.getWorkspace().getVcs().getVcsType();
        try {
            String commentId = postComment(job, markdownComment);
            if (commentId != null) {
                job.setPrCommentError(null);
            } else if (PR_COMMENT_SUPPORTED_VCS.contains(vcsType)) {
                job.setPrCommentError(buildFailureMessage(job));
            }
            return commentId;
        } catch (Exception e) {
            log.error("Error posting PR comment for job {}: {}", job.getId(), e.getMessage());
            if (PR_COMMENT_SUPPORTED_VCS.contains(vcsType)) {
                job.setPrCommentError(buildFailureMessage(job));
            }
            return null;
        }
    }

    private String buildFailureMessage(Job job) {
        return "Failed to post comment on pull request #" + job.getPrNumber()
                + ". Verify the VCS connection has write access to pull requests.";
    }

    /**
     * Tries to edit the existing thread comment in place; falls back to posting a new comment
     * if the update fails (e.g. the original comment was deleted), so the plan result is never
     * silently dropped.
     */
    private String attemptUpdateComment(Job job, String commentId, String markdownComment) {
        try {
            if (updateComment(job, commentId, markdownComment)) {
                job.setPrCommentError(null);
                return commentId;
            }
        } catch (Exception e) {
            log.error("Error updating PR comment {} for job {}: {}", commentId, job.getId(), e.getMessage());
        }
        return attemptPostComment(job, markdownComment);
    }

    private boolean updateComment(Job job, String commentId, String markdownComment) {
        switch (job.getWorkspace().getVcs().getVcsType()) {
            case GITHUB:
                return gitHubWebhookService.updatePrComment(job, commentId, markdownComment);
            case GITLAB:
                return gitLabWebhookService.updateMergeRequestNote(job, commentId, markdownComment);
            case BITBUCKET:
                return bitBucketWebhookService.updatePrComment(job, commentId, markdownComment);
            default:
                return false;
        }
    }

    /**
     * job.getTerraformPlan() is a storage pointer to the binary .tfplan file, and
     * job.getOutput() is just append-only step-completion markers - neither holds the
     * human-readable console text. The real diff/summary text lives in the plan/apply step's
     * console output, the same place the job details UI reads it from.
     *
     * <p>A custom TCL template can run extra steps after the plan/apply step itself (a
     * notification, a cost-estimation call, ...), each as its own {@link Step} with a higher
     * step number - so the highest-numbered step isn't reliably the Terraform step. Search
     * backward from the last step for the first one whose output actually contains a
     * recognizable plan/apply summary line, and only fall back to the literal last step (the
     * previous behavior) if none do - e.g. the run failed before Terraform printed one.
     */
    private String fetchStepOutputText(Job job) {
        // job.getStep() is a lazy collection: callers here include ScheduleJob's
        // doRunExecution path, which reads the job outside any open Hibernate session, so
        // touching the proxy throws LazyInitializationException. Load steps explicitly instead.
        List<Step> steps = stepRepository.findByJobId(job.getId());
        if (steps.isEmpty()) {
            return null;
        }

        List<Step> stepsNewestFirst = steps.stream()
                .sorted(Comparator.comparingInt(Step::getStepNumber).reversed())
                .collect(Collectors.toList());

        String lastStepOutput = null;
        for (Step step : stepsNewestFirst) {
            String output = readStepOutputText(job, step);
            if (lastStepOutput == null) {
                lastStepOutput = output;
            }
            if (matchRunSummary(output) != null) {
                return output;
            }
        }

        return lastStepOutput;
    }

    private String readStepOutputText(Job job, Step step) {
        try {
            String stepId = step.getId().toString();
            String liveLogs = streamingService.getCurrentLogs(stepId, "");
            if (liveLogs != null && !liveLogs.isEmpty()) {
                return stripAnsi(liveLogs);
            }

            byte[] storedOutput = storageTypeService.getStepOutput(
                    job.getOrganization().getId().toString(), String.valueOf(job.getId()), stepId);
            if (storedOutput == null || storedOutput.length == 0) {
                return null;
            }
            return stripAnsi(new String(storedOutput, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error fetching step output for job {}: {}", job.getId(), e.getMessage());
            return null;
        }
    }

    private String stripAnsi(String text) {
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    private String matchRunSummary(String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        Matcher matcher = RUN_SUMMARY_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    public Optional<String> extractRunSummary(Job job) {
        return Optional.ofNullable(matchRunSummary(fetchStepOutputText(job)));
    }

    public void postApplyDisabledNotice(Workspace workspace, Integer prNumber) {
        if (prNumber == null || prNumber == 0) return;

        Job transientJob = new Job();
        transientJob.setWorkspace(workspace);
        transientJob.setPrNumber(prNumber);

        String markdown = """
                ## Terrakube Apply

                ⚠️ Apply via PR comment is not enabled for this workspace.

                Ask a workspace admin to enable **Allow Apply via PR Comment** in the webhook settings, \
                or apply this plan from the Terrakube UI.
                """;

        postComment(transientJob, markdown);
    }

    /**
     * Reacts to a just-received "terrakube plan"/"terrakube apply" comment with an "eyes" reaction,
     * so the user gets immediate feedback the command was seen while the job is still running.
     * Shared by both the per-workspace (v1) and shared (v2) webhook paths. Bitbucket Cloud has no
     * comment-reaction API, so it's a no-op there. Failures here must never block the plan/apply.
     */
    public void acknowledgeReceipt(Workspace workspace, String commentId, Number prNumber) {
        if (commentId == null || commentId.isEmpty()) return;

        try {
            switch (workspace.getVcs().getVcsType()) {
                case GITHUB:
                    gitHubWebhookService.addCommentReaction(workspace, commentId, "eyes");
                    break;
                case GITLAB:
                    gitLabWebhookService.addNoteReaction(workspace, prNumber, commentId, "eyes");
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to acknowledge PR comment command for workspace {}: {}", workspace.getName(), e.getMessage());
        }
    }

    /**
     * Reacts to the original "terrakube plan"/"terrakube apply" comment with a checkmark or cross
     * once that job finishes, so a user watching the PR can see the command was actioned without
     * opening the (possibly long) result comment. Bitbucket Cloud has no comment-reaction API, so
     * it's a no-op there. Failures here must never affect the plan/apply result itself.
     */
    public void acknowledgeCompletion(Job job) {
        String commentId = job.getCommandCommentId();
        if (commentId == null || commentId.isEmpty() || job.getPrNumber() == null || job.getPrNumber() == 0) return;

        boolean success = job.getStatus() == JobStatus.completed;
        try {
            switch (job.getWorkspace().getVcs().getVcsType()) {
                case GITHUB:
                    gitHubWebhookService.addCommentReaction(job.getWorkspace(), commentId, success ? "+1" : "-1");
                    break;
                case GITLAB:
                    gitLabWebhookService.addNoteReaction(job.getWorkspace(), job.getPrNumber(), commentId,
                            success ? "white_check_mark" : "x");
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to acknowledge completion for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private String postComment(Job job, String markdownComment) {
        String commentId = null;
        switch (job.getWorkspace().getVcs().getVcsType()) {
            case GITHUB:
                commentId = gitHubWebhookService.postPrComment(job, markdownComment);
                break;
            case GITLAB:
                commentId = gitLabWebhookService.postMergeRequestNote(job, markdownComment);
                break;
            case BITBUCKET:
                commentId = bitBucketWebhookService.postPrComment(job, markdownComment);
                break;
            default:
                break;
        }
        return commentId;
    }

    private String jobReference(Job job) {
        if (uiUrl == null || uiUrl.isEmpty()) {
            return "#" + job.getId();
        }
        Workspace workspace = job.getWorkspace();
        String jobUrl = String.format("%s/organizations/%s/workspaces/%s/runs/%s", uiUrl,
                workspace.getOrganization().getId(), workspace.getId(), job.getId());
        return "[#" + job.getId() + "](" + jobUrl + ")";
    }

    private String statusIcon(JobStatus status) {
        switch (status) {
            case completed:
                return "✅";
            case failed:
                return "❌";
            default:
                return "⚠️";
        }
    }

    /**
     * Renders the same per-resource change list the job details UI's StructuredPlanOutput
     * already shows, as a compact markdown table - the raw ANSI-stripped console text kept in
     * the collapsible fold below is still real Terraform output, but it's runtime log text, not
     * a reviewer-friendly summary of what's actually changing. Reads the job's structured-output
     * context blob (the same one PlanStructuredOutputService/ApplyStructuredOutputService write
     * to during the run) directly from storage rather than the executor's live context API,
     * since by the time a PR comment is posted the job has already finished.
     */
    private Optional<String> renderStructuredChangesTable(Job job, boolean apply) {
        try {
            String contextJson = storageTypeService.getContext(job.getId());
            if (contextJson == null || contextJson.isBlank()) {
                return Optional.empty();
            }

            Map<String, Object> context = objectMapper.readValue(contextJson, new TypeReference<Map<String, Object>>() {
            });
            Object rawByStep = context.get(apply ? "applyStructuredOutput" : "planStructuredOutput");
            if (!(rawByStep instanceof Map<?, ?> byStep)) {
                return Optional.empty();
            }

            List<Map<String, Object>> changes = null;
            for (Object rawChanges : byStep.values()) {
                if (rawChanges instanceof List<?> list && !list.isEmpty()) {
                    changes = (List<Map<String, Object>>) (List<?>) list;
                    break;
                }
            }
            if (changes == null) {
                return Optional.empty();
            }

            StringBuilder table = new StringBuilder();
            table.append("| | Resource | Action |\n");
            table.append("|---|---|---|\n");
            int shown = 0;
            for (Map<String, Object> change : changes) {
                String action = String.valueOf(change.getOrDefault("action", "unknown"));
                if ("no-op".equals(action)) {
                    continue;
                }
                if (shown >= MAX_TABLE_ROWS) {
                    table.append("| | _").append(changes.size() - shown).append(" more resources - see full output below_ | |\n");
                    break;
                }

                Object addressRaw = change.get("address");
                String address = addressRaw == null ? "?" : String.valueOf(addressRaw);
                table.append("| ").append(actionIcon(action)).append(" | `").append(address).append("` | ")
                        .append(action).append(" |\n");
                shown++;
            }

            return shown == 0 ? Optional.empty() : Optional.of(table.toString());
        } catch (Exception e) {
            log.warn("Unable to render structured changes table for job {}: {}", job.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String actionIcon(String action) {
        return switch (action) {
            case "create" -> "🟢";
            case "delete" -> "🔴";
            case "update" -> "🔵";
            case "replace" -> "🟠";
            case "import" -> "⬇️";
            default -> "⚪";
        };
    }

    private String formatPlanComment(Job job, String planOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Terrakube Plan Output\n\n");
        sb.append("**Workspace:** ").append(job.getWorkspace().getName()).append("\n");
        sb.append("**Status:** ").append(job.getStatus()).append("\n");
        sb.append("**Job:** ").append(jobReference(job)).append("\n\n");

        String icon = statusIcon(job.getStatus());

        if (planOutput != null && !planOutput.isEmpty()) {
            String summary = matchRunSummary(planOutput);
            if (summary != null) {
                sb.append(icon).append(" ").append(summary).append("\n\n");
            }

            renderStructuredChangesTable(job, false).ifPresent(table -> sb.append(table).append("\n"));

            String content = planOutput;
            if (content.length() > MAX_COMMENT_LENGTH) {
                content = content.substring(0, MAX_COMMENT_LENGTH)
                        + "\n\n... (output truncated, see full output in Terrakube UI)";
            }
            sb.append("<details><summary>Show Plan</summary>\n\n");
            sb.append("```diff\n");
            sb.append(content);
            sb.append("\n```\n\n</details>\n\n");
        } else if (job.getStatus() == JobStatus.completed) {
            sb.append(icon).append(" No changes detected.\n\n");
        } else {
            sb.append(icon).append(" Plan failed. Check the Terrakube UI for details.\n\n");
        }

        sb.append("---\n");
        if (job.isPrApplyEnabled()) {
            sb.append("To apply this plan, comment: `terrakube apply`\n");
        } else {
            sb.append("Apply via PR comment is disabled for this workspace. Apply this plan from the Terrakube UI, ")
              .append("or ask a workspace admin to enable **Allow Apply via PR Comment**.\n");
        }
        sb.append("To re-plan, comment: `terrakube plan`\n");

        return sb.toString();
    }

    private String formatApplyComment(Job job, String output) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Terrakube Apply Output\n\n");
        sb.append("**Workspace:** ").append(job.getWorkspace().getName()).append("\n");
        sb.append("**Status:** ").append(job.getStatus()).append("\n");
        sb.append("**Job:** ").append(jobReference(job)).append("\n\n");

        String icon = statusIcon(job.getStatus());
        String summary = job.getStatus() == JobStatus.completed ? "Apply complete" : "Apply failed";
        sb.append(icon).append(" ").append(summary).append("\n\n");

        renderStructuredChangesTable(job, true).ifPresent(table -> sb.append(table).append("\n"));

        if (output != null && !output.isEmpty()) {
            String content = output;
            if (content.length() > MAX_COMMENT_LENGTH) {
                content = content.substring(0, MAX_COMMENT_LENGTH)
                        + "\n\n... (output truncated, see full output in Terrakube UI)";
            }
            sb.append("<details><summary>Show Apply Output</summary>\n\n");
            sb.append("```\n");
            sb.append(content);
            sb.append("\n```\n\n</details>\n");
        }

        return sb.toString();
    }
}
