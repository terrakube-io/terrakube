import { Button } from "antd";
import { SettingOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

type Props = {
  orgId: string;
};

export default function OrgSettingsButton({ orgId }: Props) {
  const navigate = useNavigate();
  const { permissions, loading } = useOrgPermissions(orgId);

  if (loading || !permissions.managePermission) {
    return null;
  }

  return (
    <Button
      type="text"
      size="small"
      icon={<SettingOutlined />}
      aria-label="organization settings"
      onClick={(e) => {
        e.stopPropagation();
        navigate(`/organizations/${orgId}/settings`);
      }}
    />
  );
}
