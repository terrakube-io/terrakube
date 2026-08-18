import { NotificationConfigurationList } from "@/domain/Notifications/NotificationConfigurationList";
import { NotificationDeliveryHistory } from "@/domain/Notifications/NotificationDeliveryHistory";
import { Workspace } from "../../types";

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
};

export const WorkspaceNotifications = ({ workspace, manageWorkspace }: Props) => {
  const organizationId = workspace.relationships.organization.data.id;
  return (
    <>
      <NotificationConfigurationList orgId={organizationId} workspaceId={workspace.id} managePermission={manageWorkspace} />
      <NotificationDeliveryHistory workspaceId={workspace.id} />
    </>
  );
};
