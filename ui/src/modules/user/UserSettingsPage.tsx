import { Breadcrumb, Layout } from "antd";
import { useLocation } from "react-router-dom";
import { Tokens } from "./components/PatSection/PatSection";
import { ThemeSection } from "./components/ThemeSection/ThemeSection";
import "./UserSettingsPage.css";
const { Content } = Layout;

export const UserSettingsPage = () => {
  const location = useLocation();
  const isTheme = location.pathname.includes("/settings/theme");

  return (
    <Content className="user-settings-page">
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: "Account Settings",
          },
          {
            title: isTheme ? "Theme" : "Tokens",
          },
        ]}
      />
      <div className="user-settings-content">{isTheme ? <ThemeSection /> : <Tokens />}</div>
    </Content>
  );
};
