package io.terrakube.api.plugin.cost;

import io.terrakube.api.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

/**
 * Persists workspace monthly cost columns in a standalone {@code REQUIRES_NEW} transaction
 * so persistence failures can never mark the caller's job-reconciliation transaction rollback-only.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceCostWriter {

    private final WorkspaceRepository workspaceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeCost(UUID workspaceId, BigDecimal cost, String currency, Date updatedAt) {
        workspaceRepository.updateCost(workspaceId, cost, currency, updatedAt);
    }
}
