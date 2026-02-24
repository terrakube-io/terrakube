package io.terrakube.api.plugin.state.lock;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * Represents the current lock state of a workspace.
 * Used both for API responses and internal lock tracking.
 */
@Getter
@Builder
@ToString
public class LockInfo {
    private final String lockId;
    private final String lockedBy;
    private final Instant lockedAt;
    private final String reason;
    private final Instant expiresAt;

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
