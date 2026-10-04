import { useState } from "react";
import "@/modules/organizations/utils/orgIcon.css";
import "./IconSelector.css";
import { Input, Popover } from "antd";
import { SearchOutlined } from "@ant-design/icons";
import { faIcons, getFaIcon } from "../../config/iconList";

// Get all FontAwesome 6 icon names from react-icons/fa6
const allFa6Icons = Object.keys(faIcons).filter((key) => key.startsWith("Fa"));

// Sort for easier browsing
allFa6Icons.sort();

const DEFAULT_ICON = "FaBuilding";

// Add color prop and onColorChange callback
export type IconSelectorProps = {
  value?: string;
  color?: string;
  onChange?: (icon: string) => void;
};

export const IconSelector = ({ value, color = "#000000", onChange }: IconSelectorProps) => {
  const [searchText, setSearchText] = useState("");
  // Default to FaBuilding if no value is provided
  const [selectedIcon, setSelectedIcon] = useState(value || DEFAULT_ICON);

  // Filter icons based on search text
  const filteredIcons = allFa6Icons.filter((icon) => icon.toLowerCase().includes(searchText.toLowerCase()));

  const handleIconSelect = (icon: string) => {
    setSelectedIcon(icon);
    onChange?.(icon);
  };

  const content = (
    <div className="icon-selector-panel" style={{ "--org-icon-color": color } as React.CSSProperties}>
      <Input
        aria-label="Search icons"
        placeholder="Search icons..."
        prefix={<SearchOutlined />}
        value={searchText}
        onChange={(e) => setSearchText(e.target.value)}
        className="icon-selector-search"
      />
      <div className="icon-selector-grid">
        {filteredIcons.map((icon) => {
          const IconComponent = faIcons[icon as keyof typeof faIcons];
          return (
            <div
              key={icon}
              onClick={() => handleIconSelect(icon)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  handleIconSelect(icon);
                }
              }}
              tabIndex={0}
              role="button"
              aria-label={`Select ${icon} icon`}
              className={
                selectedIcon === icon ? "icon-selector-option icon-selector-option-selected" : "icon-selector-option"
              }
            >
              <IconComponent className="org-icon" />
              <div className="icon-selector-option-label">{icon}</div>
            </div>
          );
        })}
      </div>
    </div>
  );

  const SelectedIconComponent = selectedIcon ? getFaIcon(selectedIcon) : null;

  return (
    <Popover content={content} trigger="click" placement="bottomLeft">
      <div className="icon-selector-trigger" style={{ "--org-icon-color": color } as React.CSSProperties}>
        {SelectedIconComponent ? (
          <SelectedIconComponent className="org-icon" />
        ) : (
          <span className="icon-selector-placeholder">Select an icon</span>
        )}
      </div>
    </Popover>
  );
};
