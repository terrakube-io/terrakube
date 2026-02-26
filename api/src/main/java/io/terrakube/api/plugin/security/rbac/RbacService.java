package io.terrakube.api.plugin.security.rbac;

import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.access.Access;

/**
 * Strategy interface for RBAC permission checks.
 * <p>
 * Uses role-based logic with predefined roles (admin, write, plan, read)
 * and a custom role that falls back to fine-grained boolean flags.
 */
public interface RbacService {

    // --- Organization-level (Team) checks ---

    boolean canManageWorkspace(Team team);

    boolean canManageModule(Team team);

    boolean canManageProvider(Team team);

    boolean canManageVcs(Team team);

    boolean canManageTemplate(Team team);

    boolean canManageState(Team team);

    boolean canManageCollection(Team team);

    /**
     * Whether the team can create/queue jobs (plans).
     * Maps to planJob flag or role-derived permission (admin/write/plan roles).
     */
    boolean canPlanJob(Team team);

    /**
     * Whether the team can apply jobs (approve/reject).
     * Maps to approveJob flag or role-derived permission (admin/write roles only).
     */
    boolean canApproveJob(Team team);

    /**
     * Backward-compatible check: can the team "manage" jobs?
     * True if the team can either plan or approve.
     */
    boolean canManageJob(Team team);

    // --- Workspace-level (Access) checks ---

    boolean canManageWorkspace(Access access);

    boolean canManageState(Access access);

    boolean canPlanJob(Access access);

    boolean canApproveJob(Access access);

    boolean canManageJob(Access access);
}
