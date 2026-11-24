import { WorkspaceGeneral } from "./General";
import { WorkspacePolicies } from "./Policies";
import { WorkspaceLocking } from "./Locking";
import { WorkspaceSSHKey } from "./SSHKey";
import { WorkspaceWebhook } from "./Webhook";
import { WorkspaceNotifications } from "./Notifications";
import { WorkspaceAdvanced } from "./Advanced";
import { WorkspaceStateShared } from "./StateShared";
import { WorkspaceTeamAccess } from "./TeamAccess";
import { useParams } from "react-router-dom";
import { ManagedInCodeBanner } from "@/components/settings/ManagedInCodeBanner";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";
import { useManagedInCode } from "@/modules/organizations/managedInCode";
import { Workspace, Template, VcsType } from "../../types";

type Props = {
  workspace: Workspace;
  orgTemplates: Template[];
  manageWorkspace: boolean;
  planJob?: boolean;
  vcsProvider?: VcsType;
  onWorkspaceUpdate?: () => void;
  activeSection: string;
};

export const WorkspaceSettings = ({
  workspace,
  orgTemplates,
  manageWorkspace,
  planJob = false,
  vcsProvider,
  onWorkspaceUpdate,
  activeSection,
}: Props) => {
  const handleWorkspaceUpdate = () => {
    if (onWorkspaceUpdate) {
      onWorkspaceUpdate();
    }
  };
  const { orgid } = useParams();
  const organizationId = orgid ?? sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const managed = useManagedInCode(organizationId);

  return (
    <>
      <ManagedInCodeBanner orgId={organizationId} readOnlyHere={activeSection === "general"} />
      {section()}
    </>
  );

  function section() {
    switch (activeSection) {
      case "policies":
        return (
          <WorkspacePolicies
            workspace={workspace}
            manageWorkspace={manageWorkspace}
            planJob={planJob}
            onWorkspaceUpdate={handleWorkspaceUpdate}
          />
        );
      case "locking":
        return (
          <WorkspaceLocking
            workspace={workspace}
            manageWorkspace={manageWorkspace}
            onWorkspaceUpdate={handleWorkspaceUpdate}
          />
        );
      case "sshkey":
        return (
          <WorkspaceSSHKey
            workspace={workspace}
            manageWorkspace={manageWorkspace}
            onWorkspaceUpdate={handleWorkspaceUpdate}
          />
        );
      case "webhook":
        return (
          <WorkspaceWebhook
            workspace={workspace}
            vcsProvider={vcsProvider}
            orgTemplates={orgTemplates}
            manageWorkspace={manageWorkspace}
            onWorkspaceUpdate={handleWorkspaceUpdate}
          />
        );
      case "notifications":
        return <WorkspaceNotifications workspace={workspace} manageWorkspace={manageWorkspace} />;
      case "advanced":
        return <WorkspaceAdvanced workspace={workspace} manageWorkspace={manageWorkspace} />;
      case "state-shared":
        return (
          <WorkspaceStateShared
            workspace={workspace}
            manageWorkspace={manageWorkspace}
            onWorkspaceUpdate={handleWorkspaceUpdate}
          />
        );
      case "team-access":
        return <WorkspaceTeamAccess workspace={workspace} manageWorkspace={manageWorkspace} />;
      case "general":
      default:
        return (
          <WorkspaceGeneral
            workspaceData={workspace}
            orgTemplates={orgTemplates}
            // Read-only here only for the General settings, which hold what the code sets. Locking and approving stay.
            manageWorkspace={manageWorkspace && !managed?.readOnly}
            onWorkspaceUpdate={handleWorkspaceUpdate}
          />
        );
    }
  }
};
