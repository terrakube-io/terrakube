package io.terrakube.executor.service.workspace.security;

import io.terrakube.executor.service.mode.TerraformJob;

public final class JobContextHolder {

    private static final InheritableThreadLocal<TerraformJob> CURRENT_JOB = new InheritableThreadLocal<>();

    private JobContextHolder() {
    }

    public static void set(TerraformJob job) {
        CURRENT_JOB.set(job);
    }

    public static TerraformJob get() {
        return CURRENT_JOB.get();
    }

    public static void clear() {
        CURRENT_JOB.remove();
    }
}
