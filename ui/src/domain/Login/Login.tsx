import { Button, Card, Space } from "antd";
import { mgr } from "../../config/authConfig";
import "./Login.css";
import logo from "./logo.jpg";

const Login = () => {
  return (
    <div className="login-container">
      <div className="login-wrapper">
        <Card title={<img alt="logo" className="loginLogo" src={logo} />}>
          <Space orientation="vertical">
            Sign in to Terrakube
            <Button type="primary" onClick={() => App()}>
              Login
            </Button>
          </Space>
        </Card>
      </div>{" "}
    </div>
  );
};

function App() {
  mgr.signinRedirect();
}

export default Login;
