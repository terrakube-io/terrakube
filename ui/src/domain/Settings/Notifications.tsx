import { useParams } from "react-router-dom";
import { NotificationConfigurationList } from "@/domain/Notifications/NotificationConfigurationList";

type Props = {
  managePermission?: boolean;
};

export const OrgNotifications = ({ managePermission = true }: Props) => {
  const { orgid } = useParams();
  return <NotificationConfigurationList orgId={orgid!} managePermission={managePermission} />;
};
