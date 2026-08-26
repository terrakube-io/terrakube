import { getFaIcon, FaBuilding } from "@/config/iconList";
import stringToDeterministicColor from "@/modules/utils/stringToDeterministicColor";

const DEFAULT_ICON = "FaBuilding";
const DEFAULT_COLOR = "#000000";

export function parseIconField(iconField: string | undefined, orgId: string): { iconName: string; color: string } {
  if (!iconField) {
    return { iconName: DEFAULT_ICON, color: stringToDeterministicColor(orgId) };
  }
  const [iconName, color] = iconField.split(":");
  return {
    iconName: iconName || DEFAULT_ICON,
    color: color ? color : DEFAULT_COLOR,
  };
}

export function getOrgIcon(iconName: string, color: string, fontSize = 40) {
  const IconComponent = getFaIcon(iconName) || FaBuilding;
  return <IconComponent style={{ color, fontSize }} />;
}
