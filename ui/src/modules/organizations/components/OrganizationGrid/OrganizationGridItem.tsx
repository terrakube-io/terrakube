import { Flex, Typography, Card } from "antd";
import { Link } from "react-router-dom";
import { OrganizationModel } from "../../types";
import { parseIconField, getOrgIcon } from "../../utils/orgIcon";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../../../config/actionTypes";

type Props = {
  organization: OrganizationModel;
};

export default function OrganizationGridItem({ organization }: Props) {
  const { iconName, color } = parseIconField(organization.icon, organization.id);

  const rememberOrganization = () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, organization.id);
    sessionStorage.setItem(ORGANIZATION_NAME, organization.name);
  };

  return (
    <Link
      to={`/organizations/${organization.id}/workspaces`}
      onClick={rememberOrganization}
      style={{ display: "block", color: "inherit" }}
    >
      <Card hoverable style={{ width: "100%" }}>
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
    </Link>
  );
}
