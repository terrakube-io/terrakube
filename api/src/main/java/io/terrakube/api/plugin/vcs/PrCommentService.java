package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.VcsType;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

@AllArgsConstructor
@Slf4j
@Service
public class PrCommentService {

    private static final int MAX_COMMENT_LENGTH = 60000;
    private static final Set<VcsType> PR_COMMENT_SUPPORTED_VCS = EnumSet.of(VcsType.GITHUB, VcsType.GITLAB, VcsType.BITBUCKET);

    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    BitBucketWebhookService bitBucketWebhookService;
    JobRepository jobRepository;

    public void postPlanResult(Job job) {
        if (job.getPrNumber() == null || job.getPrNumber() == 0) return;

        String planOutput = job.getTerraformPlan();
        String markdownComment = formatPlanComment(job, planOutput);

        String commentId = attemptPostComment(job, markdownComment);
        if (commentId != null) {
            job.setPrCommentId(commentId);
        }
        jobRepository.save(job);
    }

    public void postApplyResult(Job job) {
        if (job.getPrNumber() == null || job.getPrNumber() == 0) return;

        String output = job.getOutput();
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

    private String formatPlanComment(Job job, String planOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Terrakube Plan Output\n\n");
        sb.append("**Workspace:** ").append(job.getWorkspace().getName()).append("\n");
        sb.append("**Status:** ").append(job.getStatus()).append("\n");
        sb.append("**Job:** #").append(job.getId()).append("\n\n");

        if (planOutput != null && !planOutput.isEmpty()) {
            String content = planOutput;
            if (content.length() > MAX_COMMENT_LENGTH) {
                content = content.substring(0, MAX_COMMENT_LENGTH)
                        + "\n\n... (output truncated, see full output in Terrakube UI)";
            }
            sb.append("<details><summary>Show Plan</summary>\n\n");
            sb.append("```hcl\n");
            sb.append(content);
            sb.append("\n```\n\n</details>\n\n");
        } else if (job.getStatus() == JobStatus.completed) {
            sb.append("No changes detected.\n\n");
        } else {
            sb.append("Plan failed. Check the Terrakube UI for details.\n\n");
        }

        sb.append("---\n");
        sb.append("To apply this plan, comment: `terrakube apply`\n");
        sb.append("To re-plan, comment: `terrakube plan`\n");

        return sb.toString();
    }

    private String formatApplyComment(Job job, String output) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Terrakube Apply Output\n\n");
        sb.append("**Workspace:** ").append(job.getWorkspace().getName()).append("\n");
        sb.append("**Status:** ").append(job.getStatus()).append("\n");
        sb.append("**Job:** #").append(job.getId()).append("\n\n");

        if (output != null && !output.isEmpty()) {
            String content = output;
            if (content.length() > MAX_COMMENT_LENGTH) {
                content = content.substring(0, MAX_COMMENT_LENGTH)
                        + "\n\n... (output truncated, see full output in Terrakube UI)";
            }
            sb.append("<details><summary>Show Output</summary>\n\n");
            sb.append("```\n");
            sb.append(content);
            sb.append("\n```\n\n</details>\n");
        }

        return sb.toString();
    }
}
