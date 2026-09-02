import { Button } from "antd";
import { SettingOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

type Props = {
  orgId: string;
};

export default function OrgSettingsButton({ orgId }: Props) {
  const { permissions, loading } = useOrgPermissions(orgId);

  if (loading || !permissions.managePermission) {
    return null;
  }

  return (
    <Button type="text" size="small" onClick={(e) => e.stopPropagation()}>
      <Link to={`/organizations/${orgId}/settings`} aria-label="organization settings">
        <SettingOutlined />
      </Link>
    </Button>
  );
}
