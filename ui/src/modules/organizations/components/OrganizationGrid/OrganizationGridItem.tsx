import { Flex, Typography, Card } from "antd";
import stringToDeterministicColor from "@/modules/utils/stringToDeterministicColor";
import { OrganizationModel } from "../../types";
import { getFaIcon, FaBuilding } from "@/config/iconList";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../../../config/actionTypes";
const DEFAULT_ICON = "FaBuilding";
const DEFAULT_COLOR = "#000000";

// Helper to parse icon field and get icon name and color
function parseIconField(iconField: string | undefined, orgId: string): { iconName: string; color: string } {
  if (!iconField) {
    // If icon field is empty, use FaBuilding and deterministic color
    return { iconName: DEFAULT_ICON, color: stringToDeterministicColor(orgId) };
  }
  const [iconName, color] = iconField.split(":");
  return {
    iconName: iconName || DEFAULT_ICON,
    // If color is missing, use black; if icon field is empty, handled above
    color: color ? color : DEFAULT_COLOR,
  };
}

// Helper to get the icon component
function getOrgIcon(iconName: string, color: string) {
  const IconComponent = getFaIcon(iconName) || FaBuilding;
  return <IconComponent style={{ color, fontSize: 40 }} />;
}

type Props = {
  organization: OrganizationModel;
};

export default function OrganizationGridItem({ organization }: Props) {
  const { iconName, color } = parseIconField(organization.icon, organization.id);

  const handleOrganizationClick = (e: React.MouseEvent) => {
    e.preventDefault();

    // Store organization data in session storage
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, organization.id);
    sessionStorage.setItem(ORGANIZATION_NAME, organization.name);

    // Navigate with full page reload
    window.location.href = `/organizations/${organization.id}/workspaces`;
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
