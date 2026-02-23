package io.terrakube.api.plugin.security.rbac;

import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Role-based permission model inspired by HCP Terraform.
 * <p>
 * Predefined roles (stored in Team.role / Access.role):
 * <ul>
 *   <li><b>admin</b>  — full access to everything</li>
 *   <li><b>write</b>  — can plan AND apply, manage workspace settings</li>
 *   <li><b>plan</b>   — can queue plans but NOT apply</li>
 *   <li><b>read</b>   — read-only access</li>
 *   <li><b>custom</b> — falls back to the fine-grained boolean flags (planJob, approveJob, etc.)</li>
 * </ul>
 * <p>
 * For predefined roles, the boolean flags are ignored — the role determines all permissions.
 * For "custom" role, the individual boolean flags (planJob, approveJob, etc.) are used.
 * <p>
 * When a team/access has no role set (null or blank), it defaults to "custom" which
 * falls back to the boolean flags — this ensures backward compatibility for data
 * created before roles were introduced.
 */
@Slf4j
@Service
public class RbacV2Service implements RbacService {

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_WRITE = "write";
    private static final String ROLE_PLAN = "plan";
    private static final String ROLE_READ = "read";
    private static final String ROLE_CUSTOM = "custom";

    // --- Organization-level (Team) checks ---

    @Override
    public boolean canManageWorkspace(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE -> true;
            case ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isManageWorkspace();
            default -> false;
        };
    }

    @Override
    public boolean canManageModule(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN -> true;
            case ROLE_WRITE, ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isManageModule();
            default -> false;
        };
    }

    @Override
    public boolean canManageProvider(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN -> true;
            case ROLE_WRITE, ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isManageProvider();
            default -> false;
        };
    }

    @Override
    public boolean canManageVcs(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN -> true;
            case ROLE_WRITE, ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isManageVcs();
            default -> false;
        };
    }

    @Override
    public boolean canManageTemplate(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN -> true;
            case ROLE_WRITE, ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isManageTemplate();
            default -> false;
        };
    }

    @Override
    public boolean canManageState(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE -> true;
            case ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isManageState();
            default -> false;
        };
    }

    @Override
    public boolean canManageCollection(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN -> true;
            case ROLE_WRITE, ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isManageCollection();
            default -> false;
        };
    }

    @Override
    public boolean canPlanJob(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE, ROLE_PLAN -> true;
            case ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isPlanJob();
            default -> false;
        };
    }

    @Override
    public boolean canApproveJob(Team team) {
        String role = normalizeRole(team.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE -> true;
            case ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> team.isApproveJob();
            default -> false;
        };
    }

    @Override
    public boolean canManageJob(Team team) {
        // In V2, "manage job" means the team can either plan or approve
        return canPlanJob(team) || canApproveJob(team);
    }

    // --- Workspace-level (Access) checks ---

    @Override
    public boolean canManageWorkspace(Access access) {
        String role = normalizeRole(access.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE -> true;
            case ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> access.isManageWorkspace();
            default -> false;
        };
    }

    @Override
    public boolean canManageState(Access access) {
        String role = normalizeRole(access.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE -> true;
            case ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> access.isManageState();
            default -> false;
        };
    }

    @Override
    public boolean canPlanJob(Access access) {
        String role = normalizeRole(access.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE, ROLE_PLAN -> true;
            case ROLE_READ -> false;
            case ROLE_CUSTOM -> access.isPlanJob();
            default -> false;
        };
    }

    @Override
    public boolean canApproveJob(Access access) {
        String role = normalizeRole(access.getRole());
        return switch (role) {
            case ROLE_ADMIN, ROLE_WRITE -> true;
            case ROLE_PLAN, ROLE_READ -> false;
            case ROLE_CUSTOM -> access.isApproveJob();
            default -> false;
        };
    }

    @Override
    public boolean canManageJob(Access access) {
        return canPlanJob(access) || canApproveJob(access);
    }

    // --- Helpers ---

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ROLE_CUSTOM;
        }
        return role.toLowerCase().trim();
    }
}
