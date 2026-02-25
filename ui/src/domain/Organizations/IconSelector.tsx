import { useState } from "react";
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
    <div style={{ width: 600, maxHeight: 400, overflowY: "auto" }}>
      <Input
        placeholder="Search icons..."
        prefix={<SearchOutlined />}
        value={searchText}
        onChange={(e) => setSearchText(e.target.value)}
        style={{ marginBottom: 8 }}
      />
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(4, 1fr)",
          gap: 8,
        }}
      >
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
              style={{
                padding: 6,
                cursor: "pointer",
                textAlign: "center",
                border: selectedIcon === icon ? "1px solid #1890ff" : "1px solid #d9d9d9",
                borderRadius: 4,
                height: 60,
                width: "100%",
                maxWidth: 130,
                minWidth: 0,
                boxSizing: "border-box",
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
                margin: "0 auto",
              }}
            >
              <IconComponent style={{ fontSize: 24, color }} />
              <div
                style={{
                  fontSize: 9,
                  marginTop: 2,
                  color: "#666",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  width: "100%",
                }}
              >
                {icon}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );

  const SelectedIconComponent = selectedIcon ? getFaIcon(selectedIcon) : null;

  return (
    <Popover content={content} trigger="click" placement="bottomLeft">
      <div
        style={{
          border: "1px solid #d9d9d9",
          borderRadius: 4,
          padding: "4px 11px",
          cursor: "pointer",
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
          minHeight: 32,
          minWidth: 32,
        }}
      >
        {SelectedIconComponent ? (
          <SelectedIconComponent style={{ fontSize: 20, color }} />
        ) : (
          <span style={{ color: "#bfbfbf" }}>Select an icon</span>
        )}
      </div>
    </Popover>
  );
};
