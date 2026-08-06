import { WorkspaceGeneral } from "./General";
import { WorkspaceLocking } from "./Locking";
import { WorkspaceSSHKey } from "./SSHKey";
import { WorkspaceWebhook } from "./Webhook";
import { WorkspaceAdvanced } from "./Advanced";
import { WorkspaceStateShared } from "./StateShared";
import { WorkspaceTeamAccess } from "./TeamAccess";
import { Workspace, Template, VcsType } from "../../types";

type Props = {
  workspace: Workspace;
  orgTemplates: Template[];
  manageWorkspace: boolean;
  vcsProvider?: VcsType;
  onWorkspaceUpdate?: () => void;
  activeSection: string;
};

export const WorkspaceSettings = ({
  workspace,
  orgTemplates,
  manageWorkspace,
  vcsProvider,
  onWorkspaceUpdate,
  activeSection,
}: Props) => {
  const handleWorkspaceUpdate = () => {
    if (onWorkspaceUpdate) {
      onWorkspaceUpdate();
    }
  };

  switch (activeSection) {
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
          manageWorkspace={manageWorkspace}
          onWorkspaceUpdate={handleWorkspaceUpdate}
        />
      );
  }
};
