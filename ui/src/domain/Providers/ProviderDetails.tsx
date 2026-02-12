import {
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  LinkOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import {
  Button,
  Card,
  Col,
  Divider,
  Dropdown,
  message,
  Popconfirm,
  Row,
  Space,
  Typography,
} from "antd";
import { useCallback, useEffect, useMemo, useState } from "react";
import { IconContext } from "react-icons";
import { RiFolderHistoryLine } from "react-icons/ri";
import { useNavigate, useParams } from "react-router-dom";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import {
  getProvider,
  deleteProviderCascade,
} from "./providerService";
import {
  ProviderModel,
  ProviderVersionModel,
} from "./types";

type Props = {
  organizationName: string;
};

type Params = {
  orgid: string;
  providerid: string;
};

type VersionInfo = {
  id: string;
  versionNumber: string;
  protocols: string;
};

export const ProviderDetails = ({ organizationName }: Props) => {
  const { orgid, providerid } = useParams<Params>();
  const navigate = useNavigate();
  const [provider, setProvider] = useState<ProviderModel | null>(null);
  const [versions, setVersions] = useState<VersionInfo[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState(false);

  // Load provider data
  useEffect(() => {
    if (!orgid || !providerid) return;
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid);

    setLoading(true);
    getProvider(orgid, providerid)
      .then((response) => {
        setProvider(response.data);

        const versionList: VersionInfo[] = [];
        if (response.included) {
          response.included.forEach((item) => {
            if (item.type === "version") {
              const v = item as ProviderVersionModel;
              versionList.push({
                id: v.id,
                versionNumber: v.attributes.versionNumber,
                protocols: v.attributes.protocols,
              });
            }
          });
        }
        setVersions(versionList);
        if (versionList.length > 0) {
          setSelectedVersion(versionList[0].versionNumber);
        }
      })
      .catch((error) => {
        console.error("Error loading provider:", error);
        message.error("Failed to load provider details");
      })
      .finally(() => setLoading(false));
  }, [orgid, providerid]);

  // Derive provider short name and namespace
  const providerName = provider?.attributes.name || "";
  const nameParts = providerName.includes("/")
    ? providerName.split("/")
    : ["", providerName];
  const namespace = nameParts[0];
  const shortName = nameParts[nameParts.length - 1];

  // Extract source URL from description (stored as "Source: https://...")
  const sourceUrl = useMemo(() => {
    const desc = provider?.attributes.description || "";
    const match = desc.match(/https?:\/\/[^\s]+/);
    return match ? match[0] : "";
  }, [provider]);

  // Derive short repo name for display (e.g., "goharbor/terraform-provider-harbor")
  const sourceRepoName = useMemo(() => {
    if (!sourceUrl) return "";
    try {
      const url = new URL(sourceUrl);
      return url.pathname.replace(/^\//, "").replace(/\.git$/, "");
    } catch {
      return sourceUrl;
    }
  }, [sourceUrl]);

  const handleDelete = useCallback(() => {
    if (!orgid || !providerid) return;
    setDeleting(true);
    deleteProviderCascade(orgid, providerid)
      .then(() => {
        message.success("Provider deleted successfully");
        navigate(`/organizations/${orgid}/registry`);
      })
      .catch((error) => {
        console.error("Error deleting provider:", error);
        message.error("Failed to delete provider: " + (error?.message || "Unknown error"));
      })
      .finally(() => setDeleting(false));
  }, [orgid, providerid, navigate]);

  const registryHostname = useMemo(() => {
    try {
      return new URL(window._env_.REACT_APP_REGISTRY_URI).hostname;
    } catch {
      return "registry.example.com";
    }
  }, []);

  const terraformSnippet = `terraform {
  required_providers {
    ${shortName} = {
      source  = "${registryHostname}/${organizationName.toLowerCase()}/${providerName}"${selectedVersion ? `\n      version = "${selectedVersion}"` : ""}
    }
  }
}`;

  const handleCopySnippet = () => {
    navigator.clipboard.writeText(terraformSnippet).then(() => {
      message.success("Copied to clipboard");
    });
  };

  return (
    <PageWrapper
      title={shortName || "Provider"}
      subTitle={provider?.attributes.description?.replace(/https?:\/\/[^\s]+/g, "").replace(/Source:?\s*/i, "").trim() || undefined}
      loading={loading}
      loadingText="Loading Provider..."
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
        { label: "Providers", path: `/organizations/${orgid}/registry?tab=providers` },
        { label: shortName || "...", path: `/organizations/${orgid}/registry/providers/${providerid}` },
      ]}
      fluid
      innerClassName="registry-centered"
      contentClassName="registry-centered"

    >
      {provider && (
          <div>
            {/* Metadata row */}
            <Space
              size="large"
              style={{ marginTop: 12, marginBottom: 24 }}
              wrap
            >
              {namespace && (
                <Typography.Text type="secondary">
                  By <strong>{namespace}</strong>
                </Typography.Text>
              )}
              {selectedVersion && (
                <Space size={4}>
                  <IconContext.Provider value={{ size: "1.1em" }}>
                    <RiFolderHistoryLine />
                  </IconContext.Provider>
                  <Typography.Text type="secondary">
                    Version{" "}
                  </Typography.Text>
                  <Dropdown
                    menu={{
                      items: versions.map((v) => ({
                        key: v.versionNumber,
                        label: v.versionNumber,
                      })),
                      onClick: ({ key }) => setSelectedVersion(key),
                      selectedKeys: [selectedVersion],
                    }}
                    trigger={["click"]}
                  >
                    <Typography.Link style={{ fontWeight: 600 }}>
                      {selectedVersion} <DownOutlined style={{ fontSize: 10 }} />
                    </Typography.Link>
                  </Dropdown>
                </Space>
              )}
              {sourceUrl && (
                <Space size={4}>
                  <LinkOutlined />
                  <Typography.Text type="secondary">
                    Source{" "}
                    <Typography.Link
                      href={sourceUrl}
                      target="_blank"
                    >
                      {sourceRepoName} <LinkOutlined />
                    </Typography.Link>
                  </Typography.Text>
                </Space>
              )}

            </Space>

            {/* Overview content */}
            <Row gutter={32} style={{ marginTop: 16 }}>
              <Col span={16}>
                <Typography.Title level={4}>
                  Provider details
                </Typography.Title>
                <Typography.Paragraph type="secondary">
                  Select a version from the metadata above and copy the
                  configuration from the sidebar.
                </Typography.Paragraph>

              </Col>
              <Col span={8}>
                <Card
                  style={{ borderRadius: 12, border: "1px solid #e8e8e8" }}
                  styles={{ body: { padding: "20px 24px" } }}
                >
                  {/* Manage Provider dropdown */}
                  <Dropdown
                    menu={{
                      items: [
                        {
                          key: "delete",
                          label: (
                            <Popconfirm
                              title={
                                <p>
                                  Provider <b>{providerName}</b> and all
                                  its versions will be permanently
                                  deleted.
                                  <br />
                                  Are you sure?
                                </p>
                              }
                              onConfirm={handleDelete}
                              okText="Yes"
                              cancelText="No"
                              placement="left"
                            >
                              <Space>
                                <DeleteOutlined
                                  style={{ color: "#ff4d4f" }}
                                />
                                <span style={{ color: "#ff4d4f" }}>
                                  Remove from organization
                                </span>
                              </Space>
                            </Popconfirm>
                          ),
                        },
                      ],
                    }}
                    trigger={["click"]}
                  >
                    <Button
                      style={{ width: "100%" }}
                      icon={<SettingOutlined />}
                      loading={deleting}
                    >
                      Manage Provider <DownOutlined />
                    </Button>
                  </Dropdown>

                  <Divider />

                  {/* Usage Instructions */}
                  <Typography.Title level={5} style={{ marginTop: 0 }}>
                    Usage Instructions
                  </Typography.Title>
                  <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                    Copy and paste into your Terraform configuration and run{" "}
                    <Typography.Text code style={{ fontSize: 12 }}>terraform init</Typography.Text>.
                  </Typography.Text>

                  <pre
                    style={{
                      background: "#f5f5f5",
                      border: "1px solid #e8e8e8",
                      borderRadius: 6,
                      padding: 12,
                      marginTop: 12,
                      fontSize: 12,
                      lineHeight: 1.6,
                      overflow: "auto",
                      color: "#333",
                    }}
                  >
                    {terraformSnippet}
                  </pre>

                  <Button
                    icon={<CopyOutlined />}
                    onClick={handleCopySnippet}
                    style={{ marginTop: 8 }}
                  >
                    Copy configuration
                  </Button>

                  <Divider />

                  {/* Helpful links */}
                  <Typography.Text strong style={{ fontSize: 13 }}>Helpful links</Typography.Text>
                  <div style={{ marginTop: 8 }}>
                    <Space direction="vertical" size={4}>
                      {namespace && shortName && (
                        <Typography.Link
                          href={`https://registry.terraform.io/providers/${namespace}/${shortName}/latest/docs`}
                          target="_blank"
                          style={{ fontSize: 13 }}
                        >
                          Provider Documentation <LinkOutlined />
                        </Typography.Link>
                      )}
                      <Typography.Link
                        href="https://www.terraform.io/docs/language/providers/configuration.html"
                        target="_blank"
                        style={{ fontSize: 13 }}
                      >
                        Using Providers <LinkOutlined />
                      </Typography.Link>
                    </Space>
                  </div>
                  {sourceUrl && (
                    <div style={{ marginTop: 12 }}>
                      <Typography.Link
                        href={`${sourceUrl}/issues`}
                        target="_blank"
                        style={{ color: "#ff4d4f", fontSize: 13 }}
                      >
                        Report an issue <LinkOutlined />
                      </Typography.Link>
                    </div>
                  )}
                </Card>
              </Col>
            </Row>


          </div>
      )}
    </PageWrapper>
  );
};
