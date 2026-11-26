package io.terrakube.api.plugin.cost;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceCostTrackingService {

    static final BigDecimal MAX_DECIMAL_19_4 = new BigDecimal("999999999999999.9999");

    private final WorkspaceCostWriter workspaceCostWriter;
    private final TclService tclService;
    private final InfracostContextExtractor infracostContextExtractor;

    public void updateWorkspaceCostOnCompletion(Job job, List<Step> steps) {
        if (job == null || job.getWorkspace() == null || job.getWorkspace().getId() == null
                || steps == null || steps.isEmpty()) {
            return;
        }

        FlowType terminalStateFlow = findLastCompletedStateFlow(job, steps);
        if (terminalStateFlow == null) {
            // Speculative plans / plan-only / approval-only jobs must not overwrite active workspace spend.
            return;
        }

        try {
            Workspace workspace = job.getWorkspace();
            if (terminalStateFlow == FlowType.terraformDestroy) {
                String currency = sanitizeCurrency(workspace.getCostCurrency());
                workspaceCostWriter.writeCost(workspace.getId(), BigDecimal.ZERO, currency, new Date());
                log.info("Reset estimated monthly cost to 0 for workspace {} after completed destroy job {}",
                        workspace.getId(), job.getId());
                return;
            }

            Optional<InfracostSummary> summaryOpt = infracostContextExtractor.extract(job.getId(), steps);
            if (summaryOpt.isEmpty()) {
                return;
            }
            InfracostSummary summary = summaryOpt.get();
            if (!summary.isSuccess()) {
                return;
            }

            BigDecimal sanitizedCost = sanitizeCost(summary.totalMonthlyCost(), job.getId());
            if (sanitizedCost == null) {
                return;
            }
            String sanitizedCurrency = sanitizeCurrency(summary.currencyOrDefault());

            workspaceCostWriter.writeCost(workspace.getId(), sanitizedCost, sanitizedCurrency, new Date());
            log.info("Updated estimated monthly cost for workspace {} to {} {} after completed apply job {}",
                    workspace.getId(), sanitizedCost, sanitizedCurrency, job.getId());
        } catch (Exception e) {
            log.warn("Failed to update workspace cost for job {}: {}", job.getId(), e.getMessage());
        }
    }

    static BigDecimal sanitizeCost(BigDecimal raw, int jobId) {
        if (raw == null) {
            return null;
        }
        BigDecimal scaled = raw.setScale(4, RoundingMode.HALF_UP);
        if (scaled.abs().compareTo(MAX_DECIMAL_19_4) > 0) {
            log.warn("Ignoring out-of-range monthly cost {} for job {} (exceeds DECIMAL(19,4))", raw, jobId);
            return null;
        }
        return scaled;
    }

    static String sanitizeCurrency(String raw) {
        return InfracostContextExtractor.sanitizeCurrency(raw);
    }

    private FlowType findLastCompletedStateFlow(Job job, List<Step> steps) {
        if (job.getTcl() == null || job.getTcl().isBlank()) {
            return null;
        }
        FlowType lastStateFlow = null;
        boolean pendingPlanDestroy = false;
        List<Step> orderedSteps = steps.stream()
                .sorted(Comparator.comparingInt(Step::getStepNumber))
                .toList();
        for (Step step : orderedSteps) {
            if (step.getStatus() != JobStatus.completed) {
                continue;
            }
            String flowType = tclService.getFlowTypeForStep(job, step.getStepNumber());
            if (FlowType.terraformPlanDestroy.name().equals(flowType)) {
                pendingPlanDestroy = true;
            } else if (FlowType.terraformPlan.name().equals(flowType)) {
                pendingPlanDestroy = false;
            } else if (FlowType.terraformDestroy.name().equals(flowType)) {
                lastStateFlow = FlowType.terraformDestroy;
                pendingPlanDestroy = false;
            } else if (FlowType.terraformApply.name().equals(flowType)) {
                lastStateFlow = pendingPlanDestroy ? FlowType.terraformDestroy : FlowType.terraformApply;
                pendingPlanDestroy = false;
            }
        }
        return lastStateFlow;
    }
}
