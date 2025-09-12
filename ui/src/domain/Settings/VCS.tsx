import { DeleteOutlined, EditOutlined, GithubOutlined, GitlabOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Card, Col, Divider, List, Popconfirm, Row, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { SiBitbucket } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { useParams } from "react-router-dom";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { VcsModel, VcsType } from "../types";
import { AddVCS } from "./AddVCS";
import "./Settings.css";
const { Paragraph } = Typography;

type Props = {
  vcsMode?: string;
};

export const VCSSettings = ({ vcsMode }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(false);
  const [mode, setMode] = useState(vcsMode != null ? vcsMode : "list");
  const [vcs, setVCS] = useState<VcsModel[]>([]);

  const onAddVCS = () => {
    setMode("new");
  };

  const renderVCSLogo = (vcs: VcsType) => {
    switch (vcs) {
      case "GITLAB":
        return <GitlabOutlined style={{ fontSize: "20px" }} />;
      case "BITBUCKET":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <SiBitbucket />
          </IconContext.Provider>
        );
      case "AZURE_DEVOPS":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <VscAzureDevops />
          </IconContext.Provider>
        );
      case "AZURE_SP_MI":
        return (
                <IconContext.Provider value={{ size: "20px" }}>
                    <VscAzureDevops />
                </IconContext.Provider>
        );
      default:
        return <GithubOutlined style={{ fontSize: "20px" }} />;
    }
  };

  const renderVCSType = (vcs: VcsType) => {
    switch (vcs) {
      case "GITLAB":
        return "GitLab";
      case "BITBUCKET":
        return "BitBucket";
      case "AZURE_DEVOPS":
        return "Azure Devops";
      case "AZURE_SP_MI":
          return "Azure Devops";
      default:
        return "GitHub";
    }
  };

  const getConnectUrl = (vcs: VcsType, clientId: string, callbackUrl: string, endpoint: string) => {
    switch (vcs) {
      case "GITLAB":
        if (endpoint != null)
          return `${endpoint}/oauth/authorize?client_id=${clientId}&response_type=code&scope=api&&redirect_uri=${callbackUrl}`;
        else
          return `https://gitlab.com/oauth/authorize?client_id=${clientId}&response_type=code&scope=api&&redirect_uri=${callbackUrl}`;
      case "BITBUCKET":
        if (endpoint != null)
          return `${endpoint}/site/oauth2/authorize?client_id=${clientId}&response_type=code&response_type=code&scope=repository`;
        else
          return `https://bitbucket.org/site/oauth2/authorize?client_id=${clientId}&response_type=code&response_type=code&scope=repository`;
      case "AZURE_DEVOPS":
        if (endpoint != null)
          return `${endpoint}/oauth2/authorize?client_id=${clientId}&redirect_uri=${callbackUrl}&response_type=Assertion&scope=vso.code+vso.code_status`;
        else
          return `https://app.vssps.visualstudio.com/oauth2/authorize?client_id=${clientId}&redirect_uri=${callbackUrl}&response_type=Assertion&scope=vso.code+vso.code_status`;
      default:
        if (endpoint != null)
          return `${endpoint}/login/oauth/authorize?client_id=${clientId}&allow_signup=false&scope=repo`;
        else return `https://github.com/login/oauth/authorize?client_id=${clientId}&allow_signup=false&scope=repo`;
    }
  };

  const onDelete = (id: string) => {
    axiosInstance.get(`organization/${orgid}/vcs/${id}?include=workspace`).then((response) => {
      if (response.data.included != null && response.data.included.length > 0) {
        message.error(
          "This VCS is currently in use by one or more workspaces. Please remove the VCS from all workspaces before deleting it."
        );
      } else {
        axiosInstance.delete(`organization/${orgid}/vcs/${id}`).then(() => {
          loadVCS();
        });
      }
    });
  };

  const getCallBackUrl = (id: string) => {
    return `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/callback/v1/vcs/${id}`;
  };

  useEffect(() => {
    setLoading(true);
    loadVCS();
  }, [orgid]);

  const loadVCS = () => {
    axiosInstance.get(`organization/${orgid}/vcs`).then((response) => {
      setVCS(response.data.data);
      setLoading(false);
    });
  };

  return (
    <div className="setting">
      {mode != "new" ? (
        <div>
          {" "}
          <h1 style={{ paddingBottom: "10px" }}>
            VCS Providers
            <Button type="primary" onClick={onAddVCS} className="addVCS" htmlType="button" icon={<PlusOutlined />}>
              Add a VCS Provider
            </Button>{" "}
          </h1>
          <br />
          {loading ? (
            <p>Data loading...</p>
          ) : (
            <List
              className="vcsList"
              itemLayout="horizontal"
              dataSource={vcs}
              split
              renderItem={(item) => (
                <List.Item>
                  <Card
                    style={{ width: "100%" }}
                    title={
                      <span>
                        {renderVCSLogo(item.attributes.vcsType)}&nbsp;&nbsp;
                        {item.attributes.name}
                      </span>
                    }
                    actions={[
                      <div style={{ float: "right" }}>
                        <Button type="default" icon={<EditOutlined />}>
                          Edit Client
                        </Button>
                        &nbsp;&nbsp;&nbsp;
                        <Popconfirm
                          onConfirm={() => {
                            onDelete(item.id);
                          }}
                          style={{ width: "100px" }}
                          title={
                            <p>
                              Deleting this {renderVCSType(item.attributes.vcsType)} client will disconnect <br /> any
                              workspaces currently using it. <br /> This means that VCS changes will not trigger <br />{" "}
                              jobs on those workspaces. <br />
                              Are you sure?
                            </p>
                          }
                          okText="Yes"
                          cancelText="Cancel"
                        >
                          {" "}
                          <Button type="primary" icon={<DeleteOutlined />} danger>
                            Delete Client
                          </Button>
                        </Popconfirm>
                        &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
                      </div>,
                    ]}
                  >
                    <div className="paragraph">
                      <Row>
                        <Col span={6}>
                          <Typography.Text type="secondary">Callback URL</Typography.Text>
                        </Col>
                        <Col span={18}>
                          <Paragraph copyable> {getCallBackUrl(item.attributes?.callback ?? item.id)} </Paragraph>
                        </Col>
                      </Row>
                    </div>
                    <Divider />
                    <div className="paragraph">
                      <Row>
                        <Col span={6}>
                          <Typography.Text type="secondary">API URL</Typography.Text>
                        </Col>
                        <Col span={18}>
                          <Typography.Text type="secondary">{item.attributes?.apiUrl}</Typography.Text>
                        </Col>
                      </Row>
                    </div>
                    <Divider />
                    <div className="paragraph">
                      <Row>
                        <Col span={6}>
                          <Typography.Text type="secondary">Created</Typography.Text>
                        </Col>
                        <Col span={18}>
                          <Typography.Text type="secondary">{item.attributes.createdDate}</Typography.Text>
                        </Col>
                      </Row>
                    </div>
                    <Divider />
                    <div className="paragraph">
                      <Row>
                        <Col span={6}>
                          {item.attributes.status !== "COMPLETED" ? (
                            <Typography.Text type="secondary">
                              Connect to {renderVCSType(item.attributes.vcsType)}
                            </Typography.Text>
                          ) : (
                            <Typography.Text type="secondary">Connection</Typography.Text>
                          )}
                        </Col>
                        <Col span={12}>
                          {item.attributes.status !== "COMPLETED" ? (
                            <Typography.Text type="secondary">
                              Connecting to {renderVCSType(item.attributes.vcsType)} will take your{" "}
                              {renderVCSType(item.attributes.vcsType)} user through the OAuth flow to create an
                              authorization token for access to all repositories for this organization. This means that
                              your currently logged in {renderVCSType(item.attributes.vcsType)} user token will be used
                              for all {renderVCSType(item.attributes.vcsType)} API interactions by any Terrakube user
                              anywhere within the scope of <b>{sessionStorage.getItem(ORGANIZATION_NAME)}</b>.
                            </Typography.Text>
                          ) : (
                            <Typography.Text type="secondary">
                              A connection was made on {item.attributes.createdDate} by authenticating via OAuth as{" "}
                              {renderVCSType(item.attributes.vcsType)} user <b>{item.attributes.createdBy}</b>, which
                              assigned an OAuth token for use by all Terrakube users in the{" "}
                              <b>{sessionStorage.getItem(ORGANIZATION_NAME)}</b> organization.
                            </Typography.Text>
                          )}
                        </Col>
                        <Col span={6}>
                          {item.attributes.status !== "COMPLETED" ? (
                            <Button
                              type="primary"
                              target="_blank"
                              href={getConnectUrl(
                                item.attributes.vcsType,
                                item.attributes.clientId,
                                getCallBackUrl(item.attributes?.callback ?? item.id),
                                item.attributes.endpoint
                              )}
                              size="small"
                            >
                              Connect to {renderVCSType(item.attributes.vcsType)}
                            </Button>
                          ) : (
                            <span />
                          )}
                        </Col>
                      </Row>
                    </div>
                  </Card>
                </List.Item>
              )}
            />
          )}
        </div>
      ) : (
        <AddVCS setMode={setMode} loadVCS={loadVCS} />
      )}
    </div>
  );
};
