package io.terrakube.api.plugin.state.lock;

import lombok.Getter;
import lombok.ToString;

/**
 * Represents the outcome of a lock acquisition or release attempt.
 */
@Getter
@ToString
public class LockResult {
    private final boolean success;
    private final String lockId;
    private final LockInfo existingLock;
    private final String message;

    private LockResult(boolean success, String lockId, LockInfo existingLock, String message) {
        this.success = success;
        this.lockId = lockId;
        this.existingLock = existingLock;
        this.message = message;
    }

    public static LockResult acquired(String lockId) {
        return new LockResult(true, lockId, null, "Lock acquired successfully");
    }

    public static LockResult conflict(LockInfo existingLock) {
        return new LockResult(false, null, existingLock,
                String.format("Workspace is already locked by %s since %s",
                        existingLock.getLockedBy(), existingLock.getLockedAt()));
    }

    public static LockResult released() {
        return new LockResult(true, null, null, "Lock released successfully");
    }

    public static LockResult notLocked() {
        return new LockResult(false, null, null, "Workspace is not locked");
    }

    public static LockResult notOwner(LockInfo existingLock) {
        return new LockResult(false, null, existingLock,
                String.format("Lock is owned by %s. Use force-unlock to override.", existingLock.getLockedBy()));
    }
}
