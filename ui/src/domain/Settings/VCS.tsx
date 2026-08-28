import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Card, Col, Divider, Flex, List, Row, Space, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { VcsModel, VcsType } from "../types";
import { AddVCS } from "./AddVCS";
import { EditVCS } from "./EditVCS";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import VcsLogo from "@/components/display/VcsLogo";
import { Loading } from "@/components/feedback/Loading";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
const { Paragraph } = Typography;

type Props = {
  vcsMode?: "new" | "edit" | "list";
  vcsId?: string;
  managePermission?: boolean;
};

export const VCSSettings = ({ vcsMode, vcsId, managePermission = true }: Props) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [vcs, setVCS] = useState<VcsModel[]>([]);
  const [pendingDelete, setPendingDelete] = useState<VcsModel | null>(null);

  const mode: "list" | "new" | "edit" = vcsMode ?? "list";
  const editVcsId = vcsId;
  const closeEditor = () => navigate(`/organizations/${orgid}/settings/vcs`);

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
    axiosInstance
      .get(`organization/${orgid}/vcs/${id}?include=workspace`)
      .then((response) => {
        if (response.data.included != null && response.data.included.length > 0) {
          message.error(
            "This VCS is currently in use by one or more workspaces. Please remove the VCS from all workspaces before deleting it."
          );
        } else {
          axiosInstance
            .delete(`organization/${orgid}/vcs/${id}`)
            .then(() => {
              message.success("VCS provider deleted successfully");
              loadVCS();
            })
            .catch((err) => {
              message.error(getErrorMessage(err));
            });
        }
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
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
    axiosInstance
      .get(`organization/${orgid}/vcs`)
      .then((response) => {
        setVCS(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error("Failed to load VCS providers");
        }
        setLoading(false);
      });
  };

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : mode === "list" ? (
        <div>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/vcs-providers"
            title="VCS Providers"
            description="Connect version control providers so workspaces and modules can read from your repositories."
            actions={
              <Link to={`/organizations/${orgid}/settings/vcs/new`}>
                <Button type="primary" htmlType="button" icon={<PlusOutlined />} disabled={!managePermission}>
                  Add a VCS Provider
                </Button>
              </Link>
            }
          />
          <SettingsSection maxWidth="100%">
            {loading ? (
              <Loading loading description="Loading VCS providers..." />
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
                          <VcsLogo type={item.attributes.vcsType} size={20} />
                          &nbsp;&nbsp;
                          {item.attributes.name}
                        </span>
                      }
                      actions={[
                        <Flex key="actions" justify="flex-end" style={{ paddingInline: 24 }}>
                          <Space>
                            <Link to={`/organizations/${orgid}/settings/vcs/edit/${item.id}`}>
                              <Button type="default" icon={<EditOutlined />} disabled={!managePermission}>
                                Edit Client
                              </Button>
                            </Link>
                            <Button
                              type="primary"
                              icon={<DeleteOutlined />}
                              danger
                              disabled={!managePermission}
                              onClick={() => setPendingDelete(item)}
                            >
                              Delete Client
                            </Button>
                          </Space>
                        </Flex>,
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
                                authorization token for access to all repositories for this organization. This means
                                that your currently logged in {renderVCSType(item.attributes.vcsType)} user token will
                                be used for all {renderVCSType(item.attributes.vcsType)} API interactions by any
                                Terrakube user anywhere within the scope of{" "}
                                <b>{sessionStorage.getItem(ORGANIZATION_NAME)}</b>.
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
                            {item.attributes.status !== "COMPLETED" && item.attributes.connectionType === "OAUTH" ? (
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
          </SettingsSection>
          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete VCS provider"
            message={
              <>
                Deleting the {pendingDelete && renderVCSType(pendingDelete.attributes.vcsType)} client{" "}
                <strong>{pendingDelete?.attributes.name}</strong> will disconnect any workspaces currently using it.
                This means that VCS changes will not trigger jobs on those workspaces.
              </>
            }
            okText="Delete"
            onConfirm={() => {
              if (pendingDelete) onDelete(pendingDelete.id);
              setPendingDelete(null);
            }}
            onCancel={() => setPendingDelete(null)}
          />
        </div>
      ) : mode === "new" ? (
        <AddVCS setMode={closeEditor} loadVCS={loadVCS} />
      ) : (
        <EditVCS vcsId={editVcsId!} setMode={closeEditor} loadVCS={loadVCS} />
      )}
    </div>
  );
};
