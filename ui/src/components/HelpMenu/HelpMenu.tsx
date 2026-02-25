import { DownOutlined, QuestionCircleOutlined } from "@ant-design/icons";
import { useEffect, useRef, useState } from "react";
import "./HelpMenu.css";

export const HelpMenu = () => {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener("mousedown", handleClickOutside);
    }

    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [isOpen]);

  const handleToggle = () => {
    setIsOpen(!isOpen);
  };

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

  return (
    <div className="help-menu-container" ref={containerRef}>
      <button className="help-menu-button" onClick={handleToggle} aria-expanded={isOpen} aria-label="help menu">
        <QuestionCircleOutlined className="help-menu-icon" />
        <DownOutlined className="help-menu-arrow" />
      </button>

      {isOpen && (
        <div className="help-menu-dropdown">
          <div className="help-menu-header">Help & Support</div>
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
    </div>
  );
};

export default HelpMenu;
