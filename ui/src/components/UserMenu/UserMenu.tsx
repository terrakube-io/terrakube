import { DownOutlined, PoweroffOutlined, SettingOutlined, UserOutlined } from "@ant-design/icons";
import { Avatar } from "antd";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { useAuth } from "../../config/authConfig";
import getUserFromStorage from "../../config/authUser";
import "./UserMenu.css";

export const UserMenu = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [username, setUsername] = useState<string>();
  const containerRef = useRef<HTMLDivElement>(null);
  const auth = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    const user = getUserFromStorage();
    if (user && user.profile?.name) {
      setUsername(user.profile.name);
    }
  }, []);

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

  const handleUserSettings = () => {
    setIsOpen(false);
    navigate(`/settings/tokens`);
  };

  const signOutClickHandler = () => {
    setIsOpen(false);
    auth.removeUser();
    sessionStorage.removeItem(ORGANIZATION_NAME);
    sessionStorage.removeItem(ORGANIZATION_ARCHIVE);
  };

  return (
    <div className="user-menu-container" ref={containerRef}>
      <button className="user-menu-button" onClick={handleToggle} aria-expanded={isOpen} aria-label="user menu">
        <Avatar className="user-menu-avatar" size="small" icon={<UserOutlined />} />
        <DownOutlined className="user-menu-arrow" />
      </button>

      {isOpen && (
        <div className="user-menu-dropdown">
          <div className="user-menu-header">
            <span className="user-menu-signed-in">Signed in as</span>
            <span className="user-menu-username">{username}</span>
          </div>
          <div className="user-menu-item" onClick={handleUserSettings}>
            <SettingOutlined className="user-menu-item-icon" />
            <span>Account settings</span>
          </div>
          <div className="user-menu-item" onClick={signOutClickHandler}>
            <PoweroffOutlined className="user-menu-item-icon" />
            <span>Sign out</span>
          </div>
        </div>
      )}
    </div>
  );
};

export default UserMenu;
