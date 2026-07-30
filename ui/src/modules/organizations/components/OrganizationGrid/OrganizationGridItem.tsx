import { Flex, Typography, Card } from "antd";
import { useNavigate } from "react-router-dom";
import { OrganizationModel } from "../../types";
import { parseIconField, getOrgIcon } from "../../utils/orgIcon";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../../../config/actionTypes";

type Props = {
  organization: OrganizationModel;
};

export default function OrganizationGridItem({ organization }: Props) {
  const navigate = useNavigate();
  const { iconName, color } = parseIconField(organization.icon, organization.id);

  const handleOrganizationClick = (e: React.MouseEvent) => {
    e.preventDefault();

    // Store organization data in session storage
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, organization.id);
    sessionStorage.setItem(ORGANIZATION_NAME, organization.name);

    navigate(`/organizations/${organization.id}/workspaces`);
  };

  return (
    <Card hoverable style={{ width: "100%" }} onClick={handleOrganizationClick}>
      <Flex gap="small" align="center">
        <div className="org-card-icon">{getOrgIcon(iconName, color)}</div>
        <Flex vertical gap="0">
          <Typography.Text className="org-card-title" ellipsis>
            {organization.name}
          </Typography.Text>
          <Typography.Text type="secondary">
            {organization.description || "No description set for this organization"}
          </Typography.Text>
        </Flex>
      </Flex>
    </Card>
  );
}
