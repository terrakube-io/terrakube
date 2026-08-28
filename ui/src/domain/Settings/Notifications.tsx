import { useParams } from "react-router-dom";
import { NotificationConfigurationList } from "@/domain/Notifications/NotificationConfigurationList";

type Props = {
  editorMode?: "new" | "edit";
  editorId?: string;
  managePermission?: boolean;
};

export const OrgNotifications = ({ editorMode, editorId, managePermission = true }: Props) => {
  const { orgid } = useParams();
  return (
    <NotificationConfigurationList
      orgId={orgid!}
      basePath={`/organizations/${orgid}/settings/notifications`}
      editorMode={editorMode}
      editorId={editorId}
      managePermission={managePermission}
    />
  );
};
