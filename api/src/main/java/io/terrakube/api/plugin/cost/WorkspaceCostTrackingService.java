package io.terrakube.api.plugin.cost;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceCostTrackingService {

    private final WorkspaceRepository workspaceRepository;
    private final TclService tclService;
    private final InfracostContextExtractor infracostContextExtractor;

    public void updateWorkspaceCostOnCompletion(Job job, List<Step> steps) {
        if (job == null || job.getWorkspace() == null || steps == null || steps.isEmpty()) {
            return;
        }

        FlowType terminalStateFlow = findLastCompletedStateFlow(job, steps);
        if (terminalStateFlow == null) {
            // Speculative plans / plan-only / approval-only jobs must not overwrite active workspace spend.
            return;
        }

        try {
            Workspace workspace = job.getWorkspace();
            if (workspace.getId() != null) {
                workspace = workspaceRepository.findById(workspace.getId()).orElse(workspace);
            }

            if (terminalStateFlow == FlowType.terraformDestroy) {
                workspace.setCurrentMonthlyCost(BigDecimal.ZERO);
                if (workspace.getCostCurrency() == null || workspace.getCostCurrency().isBlank()) {
                    workspace.setCostCurrency("USD");
                }
                workspace.setCostUpdatedAt(new Date());
                workspaceRepository.save(workspace);
                log.info("Reset estimated monthly cost to 0 for workspace {} after completed destroy job {}",
                        workspace.getId(), job.getId());
                return;
            }

            Optional<InfracostSummary> summaryOpt = infracostContextExtractor.extract(job.getId());
            if (summaryOpt.isEmpty()) {
                return;
            }
            InfracostSummary summary = summaryOpt.get();
            if (!summary.isSuccess() || summary.totalMonthlyCost() == null) {
                return;
            }

            workspace.setCurrentMonthlyCost(summary.totalMonthlyCost());
            workspace.setCostCurrency(summary.currencyOrDefault());
            workspace.setCostUpdatedAt(new Date());
            workspaceRepository.save(workspace);
            log.info("Updated estimated monthly cost for workspace {} to {} {} after completed apply job {}",
                    workspace.getId(), summary.totalMonthlyCost(), summary.currencyOrDefault(), job.getId());
        } catch (Exception e) {
            log.warn("Failed to update workspace cost for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private FlowType findLastCompletedStateFlow(Job job, List<Step> steps) {
        if (job.getTcl() == null || job.getTcl().isBlank()) {
            return null;
        }
        FlowType lastStateFlow = null;
        List<Step> orderedSteps = steps.stream()
                .sorted(Comparator.comparingInt(Step::getStepNumber))
                .toList();
        for (Step step : orderedSteps) {
            if (step.getStatus() != JobStatus.completed) {
                continue;
            }
            String flowType = tclService.getFlowTypeForStep(job, step.getStepNumber());
            if (FlowType.terraformApply.name().equals(flowType)) {
                lastStateFlow = FlowType.terraformApply;
            } else if (FlowType.terraformDestroy.name().equals(flowType)) {
                lastStateFlow = FlowType.terraformDestroy;
            }
        }
        return lastStateFlow;
    }
}
