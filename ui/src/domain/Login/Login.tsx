import { Button, ConfigProvider, Typography, theme } from "antd";
import { useState } from "react";
import { mgr } from "../../config/authConfig";
import { getUiRedirectUri } from "../../config/basePath";
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
  const [signinError, setSigninError] = useState<string | null>(null);
  const [isSigningIn, setIsSigningIn] = useState(false);

  const handleSignIn = () => {
    setSigninError(null);
    setIsSigningIn(true);
    // signinRedirect() can reject (e.g. identity provider unreachable) before it navigates away.
    mgr.signinRedirect({ state: getUiRedirectUri() }).catch(() => {
      setIsSigningIn(false);
      setSigninError("Unable to reach the identity provider. Please try again in a moment.");
    });
  };

  return (
    <div className="login-container" style={{ backgroundColor: token.colorBgLayout }}>
      <div className="login-card" style={{ backgroundColor: token.colorBgContainer }}>
        <img src={logo} alt="Terrakube" className="login-logo" />
        <Title level={3}>Sign in to Terrakube</Title>
        <Text type="secondary">Click below to continue with your identity provider.</Text>
        <Button type="primary" block size="large" loading={isSigningIn} onClick={handleSignIn}>
          Sign in
        </Button>
        {signinError && <Text type="danger">{signinError}</Text>}
      </div>
    </div>
  );
};

export default Login;
