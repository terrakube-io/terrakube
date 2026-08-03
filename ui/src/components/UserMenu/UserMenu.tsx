import { DownOutlined, PoweroffOutlined, SettingOutlined, UserOutlined } from "@ant-design/icons";
import { Avatar, Dropdown } from "antd";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { useAuth } from "../../config/authConfig";
import getUserFromStorage from "../../config/authUser";
import getGravatarUrl from "@/modules/utils/gravatar";
import "./UserMenu.css";

export const UserMenu = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [username, setUsername] = useState<string>();
  const [avatarUrl, setAvatarUrl] = useState<string>();
  const auth = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    const user = getUserFromStorage();
    if (user && user.profile?.name) {
      setUsername(user.profile.name);
    }
    if (user?.profile?.email) {
      getGravatarUrl(user.profile.email).then(setAvatarUrl);
    }
  }, []);

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
    <Dropdown
      trigger={["click"]}
      open={isOpen}
      onOpenChange={setIsOpen}
      placement="bottomRight"
      popupRender={() => (
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
    >
      <button type="button" className="user-menu-button" aria-expanded={isOpen} aria-label="user menu">
        <Avatar className="user-menu-avatar" size="small" src={avatarUrl} icon={<UserOutlined />} />
        <DownOutlined className="user-menu-arrow" />
      </button>
    </Dropdown>
  );
};

export default UserMenu;
