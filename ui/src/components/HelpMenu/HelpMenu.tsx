import { ApiOutlined, DownOutlined, QuestionCircleOutlined } from "@ant-design/icons";
import { Dropdown } from "antd";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import "./HelpMenu.css";

export const HelpMenu = () => {
  const [isOpen, setIsOpen] = useState(false);
  const navigate = useNavigate();

  const helpItems = [
    {
      key: "documentation",
      label: "Documentation",
      href: "https://docs.terrakube.io/",
    },
    {
      key: "github",
      label: "GitHub",
      href: "https://github.com/terrakube-io/terrakube",
    },
    {
      key: "community",
      label: "Community (Slack)",
      href: "https://join.slack.com/t/terrakubeworkspace/shared_invite/zt-2cx6yn95t-2CTBGvsQhBQJ5bfbG4peFg",
    },
  ];

  const handleApiDocs = () => {
    setIsOpen(false);
    navigate("/api-docs");
  };

  return (
    <Dropdown
      trigger={["click"]}
      open={isOpen}
      onOpenChange={setIsOpen}
      placement="bottomRight"
      popupRender={() => (
        <div className="help-menu-dropdown">
          <div className="help-menu-header">Help & Support</div>
          <div className="help-menu-item" onClick={handleApiDocs}>
            <ApiOutlined className="help-menu-item-icon" />
            <span>API Docs</span>
          </div>
          {helpItems.map((item) => (
            <a
              key={item.key}
              className="help-menu-item"
              href={item.href}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => setIsOpen(false)}
            >
              {item.label}
            </a>
          ))}
        </div>
      )}
    >
      <button type="button" className="help-menu-button" aria-expanded={isOpen} aria-label="help menu">
        <QuestionCircleOutlined className="help-menu-icon" />
        <DownOutlined className="help-menu-arrow" />
      </button>
    </Dropdown>
  );
};

export default HelpMenu;
