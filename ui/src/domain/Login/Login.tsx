import { Button, ConfigProvider, Typography, theme } from "antd";
import { mgr } from "../../config/authConfig";
import {
  ColorSchemeOption,
  ThemeMode,
  defaultColorScheme,
  defaultThemeMode,
  getThemeConfig,
} from "../../config/themeConfig";
import logo from "./logo.svg";
import "./Login.css";

const { Title, Text } = Typography;

const Login = () => {
  const savedScheme = (localStorage.getItem("terrakube-color-scheme") as ColorSchemeOption) || defaultColorScheme;
  const savedThemeMode = (localStorage.getItem("terrakube-theme-mode") as ThemeMode) || defaultThemeMode;

  return (
    <ConfigProvider theme={getThemeConfig(savedScheme, savedThemeMode)}>
      <LoginContent />
    </ConfigProvider>
  );
};

const LoginContent = () => {
  const { token } = theme.useToken();

  return (
    <div className="login-container" style={{ backgroundColor: token.colorBgLayout }}>
      <div className="login-card" style={{ backgroundColor: token.colorBgContainer }}>
        <img src={logo} alt="Terrakube" className="login-logo" />
        <Title level={3}>Sign in to Terrakube</Title>
        <Text type="secondary">Click below to continue with your identity provider.</Text>
        <Button type="primary" block size="large" onClick={() => mgr.signinRedirect()}>
          Sign in
        </Button>
      </div>
    </div>
  );
};

export default Login;
