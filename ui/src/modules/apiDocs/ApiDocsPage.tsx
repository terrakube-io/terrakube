import { LeftOutlined } from "@ant-design/icons";
import { Spin } from "antd";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiReferenceReact } from "@scalar/api-reference-react";
import "@scalar/api-reference-react/style.css";
import getUserFromStorage from "@/config/authUser";
import { axiosRegistry } from "@/config/axiosConfig";
import "./ApiDocsPage.css";

type RequestBuilder = {
  headers: { set: (name: string, value: string) => void };
};

function attachBearerToken({ requestBuilder }: { requestBuilder: RequestBuilder }) {
  const user = getUserFromStorage();
  if (user?.access_token) {
    requestBuilder.headers.set("Authorization", `Bearer ${user.access_token}`);
  }
}

export const ApiDocsPage = () => {
  const [spec, setSpec] = useState<object | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    axiosRegistry.get("/doc").then((response) => {
      setSpec(response.data);
    });
  }, []);

  return (
    <div className="api-docs-page">
      <div className="api-docs-topbar">
        <button type="button" className="api-docs-back-link" onClick={() => navigate("/")}>
          <LeftOutlined /> Back to Terrakube
        </button>
      </div>
      <div className="api-docs-content">
        {spec ? (
          <ApiReferenceReact
            configuration={{
              content: spec,
              // The spec's `servers` entry is a relative path (Elide doesn't know its own public
              // hostname when generating it) - without this, Scalar resolves it against the docs
              // page's own origin instead of the API's.
              baseServerURL: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
              onBeforeRequest: attachBearerToken,
            }}
          />
        ) : (
          <div className="api-docs-loading">
            <Spin />
          </div>
        )}
      </div>
    </div>
  );
};

export default ApiDocsPage;
