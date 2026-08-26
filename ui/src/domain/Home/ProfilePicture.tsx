import { PoweroffOutlined, QuestionCircleOutlined, UserOutlined } from "@ant-design/icons";
import { Avatar, Dropdown, message } from "antd";
import { ItemType } from "antd/es/menu/interface";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { useAuth } from "../../config/authConfig";
import getUserFromStorage from "../../config/authUser";
import "./Home.css";

export const ProfilePicture = () => {
  const [username, setUsername] = useState<string>();
  const auth = useAuth();
  const navigate = useNavigate();

  const handleUserSettings = () => {
    navigate(`/settings/tokens`);
  };

  const signOutClickHandler = () => {
    auth.removeUser();
    sessionStorage.removeItem(ORGANIZATION_NAME);
    sessionStorage.removeItem(ORGANIZATION_ARCHIVE);
  };

  useEffect(() => {
    const user = getUserFromStorage();
    if (user && user.profile?.name) {
      setUsername(user.profile.name);
    }
  }, []);

  const menuItems: ItemType[] = [
    {
      key: "user-id",
      label: (
        <>
          Signed in as <br />
          <b>{username}</b>
        </>
      ),
      onClick: () => message.info("Coming Soon"),
    },
    {
      type: "divider",
    },
    {
      key: "user-settings",
      icon: <UserOutlined />,
      label: "User Settings",
      onClick: handleUserSettings,
    },
    {
      key: "help",
      icon: <QuestionCircleOutlined />,
      label: (
        <a target="_blank" rel="noopener noreferrer" href="https://docs.terrakube.io/">
          Help
        </a>
      ),
    },
    {
      key: "sign-out",
      icon: <PoweroffOutlined />,
      label: "Sign Out",
      onClick: signOutClickHandler,
    },
  ];

  return (
    <>
      <Dropdown
        menu={{
          items: menuItems,
          theme: "dark",
        }}
        trigger={["click"]}
      >
        <Avatar className="avatar-hover-effect" size="default" icon={<UserOutlined />} />
      </Dropdown>
    </>
  );
};
