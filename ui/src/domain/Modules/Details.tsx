import {
  ArrowLeftOutlined,
  ClockCircleOutlined,
  CloudOutlined,
  DeleteOutlined,
  DownOutlined,
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
  Tabs,
  Tag,
  theme,
  Typography,
} from "antd";
import { Buffer } from "buffer";
import { lazy, Suspense, useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { FaAws } from "@/config/iconList";
import { VscAzure } from "react-icons/vsc";
import { useNavigate, useParams } from "react-router-dom";
import LoadingFallback from "@/components/feedback/LoadingFallback";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { ModuleModel, ModuleVersionAttributes, VcsType } from "../types";
import { compareVersions } from "../Workspaces/Workspaces";
import "./Module.css";
import VcsLogo from "@/components/display/VcsLogo";
import { relativeTime } from "@/modules/utils/dates";
import VersionStatusModal, {
  recommendedVersion,
  VersionStatus,
  VersionStatusAlert,
  versionMenuItems,
  versionStatusChangeMessage,
} from "@/components/modals/VersionStatusModal";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

const Markdown = lazy(async () => {
  const [{ default: ReactMarkdown }, { default: remarkGfm }, { default: rehypeRaw }] = await Promise.all([
    import("react-markdown"),
    import("remark-gfm"),
    import("rehype-raw"),
  ]);

  const MarkdownWithPlugins = ({ children }: { children: string }) => {
    return (
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeRaw]}>
        {children}
      </ReactMarkdown>
    );
  };

  return { default: MarkdownWithPlugins };
});

type Props = {
  organizationName: string;
};

type Params = {
  orgid: string;
  id: string;
};

type ModuleVersionItem = ModuleVersionAttributes & { id: string };

export const ModuleDetails = ({ organizationName }: Props) => {
  const { orgid, id } = useParams<Params>();
  const [module, setModule] = useState<ModuleModel>();
  const [moduleName, setModuleName] = useState("...");
  const [version, setVersion] = useState("...");
  const [allVersions, setAllVersions] = useState<ModuleVersionItem[]>([]);
  const [versionStatusOpen, setVersionStatusOpen] = useState(false);
  const [vcsProvider, setVCSProvider] = useState<VcsType>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [markdown, setMarkdown] = useState("loading...");
  const [hclObject, setHclObject] = useState<any>(null);
  const [inputs, setInputs] = useState("Inputs");
  const [loadingInputs, setLoadingInputs] = useState("loading...");
  const [loadingOutputs, setLoadingOutputs] = useState("loading...");
  const [loadingResources, setLoadingResources] = useState("loading...");
  const [outputs, setOutputs] = useState("Outputs");
  const [resources, setResources] = useState("Resources");
  const [submodules, setSubmodules] = useState<string[]>([]);
  const [submodule, setSubmodule] = useState("");
  const [submodulePath, setSubmodulePath] = useState("");
  const navigate = useNavigate();
  const { permissions } = useOrgPermissions();

  const {
    token: {
      colorBgContainer,
      colorBgTextHover,
      colorBorder,
      colorPrimary,
      colorPrimaryHover,
      colorFillSecondary,
      colorFillTertiary,
    },
  } = theme.useToken();

  // Add CSS variables for markdown theming
  useEffect(() => {
    document.documentElement.style.setProperty("--code-bg", colorBgTextHover);
    document.documentElement.style.setProperty("--border-color", colorBorder);
    document.documentElement.style.setProperty("--ant-primary-color", colorPrimary);
    document.documentElement.style.setProperty("--ant-primary-color-hover", colorPrimaryHover);
    document.documentElement.style.setProperty("--table-header-bg", colorFillSecondary);
    document.documentElement.style.setProperty("--table-row-bg", colorFillTertiary);
  }, [colorBgTextHover, colorBorder, colorPrimary, colorPrimaryHover, colorFillSecondary, colorFillTertiary]);

  // Renders the provider icon
  const renderLogo = (provider: string) => {
    switch (provider) {
      case "azurerm":
        return (
          <IconContext.Provider value={{ color: "#008AD7", size: "1.5em" }}>
            <VscAzure />
          </IconContext.Provider>
        );
      case "aws":
        return (
          <IconContext.Provider value={{ color: "#232F3E", size: "1.5em" }}>
            <FaAws />
          </IconContext.Provider>
        );
      default:
        return <CloudOutlined />;
    }
  };

  const selectedVersion = allVersions.find((v) => v.version === version);
  const versionsNewestFirst = [...allVersions].sort((a, b) => compareVersions(b.version, a.version));
  const upgradeTo = recommendedVersion(versionsNewestFirst)?.version;

  const saveVersionStatus = async (status: Required<VersionStatus>) => {
    if (!selectedVersion) return;
    try {
      await axiosInstance.patch(
        `organization/${orgid}/module/${id}/version/${selectedVersion.id}`,
        { data: { type: "module_version", id: selectedVersion.id, attributes: status } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      );
      setAllVersions((versions) => versions.map((v) => (v.id === selectedVersion.id ? { ...v, ...status } : v)));
      setVersionStatusOpen(false);
      message.success(versionStatusChangeMessage(selectedVersion.version, status, "module"));
    } catch (err) {
      message.error("Failed to update version: " + getErrorMessage(err));
    }
  };

  // Removed versions are no longer served by the registry, so there is nothing to load for them.
  const showVersion = (path: string, selected: string, versions: ModuleVersionItem[]) => {
    setVersion(selected);
    if (versions.find((v) => v.version === selected)?.removed) {
      setHclObject(null);
      setMarkdown("_The README is not available because this version was removed from the registry._");
      setLoadingInputs("Not available for removed versions");
      setLoadingOutputs("Not available for removed versions");
      setLoadingResources("Not available for removed versions");
      return;
    }
    setMarkdown("loading...");
    loadReadme(path, selected);
    loadModuleDetails(path, selected);
  };

  const handleClick = (e: { key: string }) => {
    if (module) {
      showVersion(module.attributes.registryPath, e.key, allVersions);
    } else {
      setMarkdown("Failed to load module");
    }
  };

  // if submodule is empty then load the submodules dropdown list
  // if submodule is empty then load the root module
  // if submodule is not empty then load the specific submodule tf files
  async function readFiles(url: string, submodule = "") {
    const { unzip } = await import("unzipit");
    const { entries } = await unzip(url);
    let hclString = "";
    const modules: string[] = [];

    for (const [name, entry] of Object.entries(entries)) {
      if (submodule === "") {
        if (name.includes("modules/")) {
          const moduleName = name.split("/")[1];
          if (!modules.includes(moduleName) && moduleName !== "") {
            modules.push(moduleName);
          }
        }
        if (name.includes(".tf") && !name.includes("/")) {
          const contentText = await entry.text();
          hclString += "\n" + contentText;
        }
        setSubmodules(modules.sort());
      } else {
        if (name.includes(".tf") && name.includes("modules/" + submodule + "/")) {
          const contentText = await entry.text();
          hclString += "\n" + contentText;
        }
        if (name.includes("modules/" + submodule + "/README.md")) {
          const readme = await entry.text();
          setMarkdown(readme);
        }
      }
    }

    const hcl = await import("hcl2-parser");
    const hclResult = hcl.parseToObject(hclString);
    if (hclResult) {
      setHclObject(hclResult[0]);
      if (hclResult[0]?.variable) {
        setInputs(`Inputs (${Object.keys(hclResult[0]?.variable)?.length})`);
      } else {
        setInputs("Inputs");
        setLoadingInputs("No inputs");
      }
      if (hclResult[0]?.output) {
        setOutputs(`Outputs (${Object.keys(hclResult[0]?.output)?.length})`);
      } else {
        setOutputs("Outputs");
        setLoadingOutputs("No outputs");
      }
      if (hclResult[0]?.resource) {
        setResources(`Resources (${Object.keys(hclResult[0]?.resource)?.length})`);
      } else {
        setResources("Resources");
        setLoadingResources("No resources");
      }
    }
  }

  const onClickSubmodule = (e: { key: string }) => {
    setMarkdown("loading...");
    setSubmodule(e.key);
    setSubmodulePath("//modules/" + e.key);
    loadModuleDetails(module!.attributes.registryPath, version, e.key);
    if (markdown === "loading...") {
      setMarkdown("");
    }
  };

  const handleClickBack = () => {
    setMarkdown("loading...");
    setSubmodule("");
    setSubmodulePath("");
    loadModuleDetails(module!.attributes.registryPath, version, "");
    loadReadme(module!.attributes.registryPath, version);
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/module/${id}`)
      .then(() => {
        message.success("Module deleted successfully");
        navigate(`/organizations/${orgid}/registry`);
      })
      .catch((error) => {
        message.error("Error deleting module " + error);
      });
  };

  async function loadReadmeFile(text: string) {
    if (text != null) {
      const textReadme = Buffer.from(text, "base64").toString();
      setMarkdown(textReadme);
    } else {
      setMarkdown("");
    }
  }

  useEffect(() => {
    setLoading(true);
    setError(null);
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid!);
    axiosInstance
      .get(`organization/${orgid}/module/${id}?include=vcs,version`)
      .then((response) => {
        setModule(response.data.data);
        setModuleName(response.data.data.attributes.name);
        const versions = setModuleInclude(response.data.included, setVCSProvider, setAllVersions);
        showVersion(
          response.data.data.attributes.registryPath,
          defaultVersion(response.data.data.attributes.latestVersion, versions),
          versions
        );
      })
      .catch((err) => {
        setError(getErrorMessage(err));
      })
      .finally(() => {
        setLoading(false);
      });
  }, [orgid, id]);

  const loadModuleDetails = async (path: string, version: string, submodule = "") => {
    setLoadingInputs("loading...");
    setLoadingOutputs("loading...");
    setLoadingResources("loading...");
    setHclObject(null);
    axiosInstance
      .get(`${window._env_.REACT_APP_REGISTRY_URI}/terraform/modules/v1/${path}/${version}/download`)
      .then((resp) => {
        readFiles(resp.headers["x-terraform-get"], submodule);
      })
      .catch((err) => {
        console.error("Error loading module details:", err);
        setLoadingInputs("Failed to load");
        setLoadingOutputs("Failed to load");
        setLoadingResources("Failed to load");
      });
  };

  const loadReadme = (path: string, version: string) => {
    axiosInstance
      .get(`${window._env_.REACT_APP_REGISTRY_URI}/terraform/readme/v1/${path}/${version}/download`)
      .then((resp) => {
        loadReadmeFile(resp.data.content);
      })
      .catch(() => {
        setMarkdown("");
      });
  };

  return (
    <PageWrapper
      title={moduleName}
      subTitle={module?.attributes.description}
      loading={loading}
      loadingText="Loading Module..."
      error={error ? { title: error.includes("permission") ? "Access Denied" : "Error", message: error } : undefined}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
        { label: moduleName, path: `/organizations/${orgid}/registry/${id}` },
      ]}
      width="reading"
    >
      {module && (
        <div>
          <Row>
            <Col span={16}>
              <Space orientation="vertical" style={{ marginTop: "10px", width: "95%" }}>
                {submodule === "" && (
                  <>
                    <Space size="large" wrap>
                      <Typography.Text type="secondary">Published by {organizationName}</Typography.Text>
                      <Typography.Text type="secondary">
                        Provider {renderLogo(module.attributes.provider)} {module.attributes.provider}
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        Version {version}{" "}
                        <Dropdown
                          menu={{
                            items: versionMenuItems(versionsNewestFirst, (v) => v.version),
                            onClick: handleClick,
                          }}
                          trigger={["click"]}
                        >
                          <button
                            type="button"
                            style={{
                              background: "none",
                              border: "none",
                              padding: 0,
                              color: "var(--tk-accent)",
                              cursor: "pointer",
                            }}
                          >
                            Change <DownOutlined />
                          </button>
                        </Dropdown>
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        <ClockCircleOutlined /> Published {relativeTime(module.attributes.createdDate)}
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        Source <VcsLogo type={vcsProvider} />{" "}
                        {module.attributes.source && (
                          <a href={fixSshURL(module.attributes.source)} target="_blank" rel="noopener noreferrer">
                            {new URL(fixSshURL(module.attributes.source)).pathname.replace(".git", "").substring(1)}
                          </a>
                        )}
                      </Typography.Text>
                    </Space>
                    {selectedVersion && (
                      <VersionStatusAlert version={version} status={selectedVersion} upgradeTo={upgradeTo} />
                    )}
                    <Divider style={{ margin: "16px 0" }} />
                    <Typography.Title level={4} style={{ margin: 0 }}>
                      Module Details
                    </Typography.Title>
                    <Typography.Text type="secondary">Explore relevant submodules and examples</Typography.Text>
                    {submodules.length > 0 && (
                      <Dropdown
                        menu={{
                          items: submodules.map((name) => ({ key: name, label: name })),
                          onClick: onClickSubmodule,
                        }}
                        trigger={["click"]}
                      >
                        <Button style={{ marginTop: "10px", fontSize: ".75rem" }}>
                          <Space>
                            Submodules
                            <DownOutlined />
                          </Space>
                        </Button>
                      </Dropdown>
                    )}
                  </>
                )}

                {submodule !== "" && (
                  <>
                    <div>
                      <Button
                        style={{ paddingLeft: "0px", marginBottom: "10px" }}
                        type="link"
                        onClick={handleClickBack}
                        icon={<ArrowLeftOutlined />}
                      >
                        Back to {moduleName}
                      </Button>
                      <h2 className="moduleTitle">
                        submodules/
                        <Dropdown
                          menu={{
                            items: submodules.map((name) => ({ key: name, label: name })),
                            onClick: onClickSubmodule,
                          }}
                          trigger={["click"]}
                        >
                          <button
                            type="button"
                            style={{
                              color: "black",
                              background: "none",
                              border: "none",
                              padding: 0,
                              cursor: "pointer",
                              font: "inherit",
                            }}
                          >
                            {submodule} <DownOutlined />
                          </button>
                        </Dropdown>
                      </h2>
                    </div>
                  </>
                )}

                <Tabs
                  className="moduleTabs"
                  defaultActiveKey="1"
                  items={[
                    {
                      label: "Readme",
                      key: "1",
                      children: (
                        <div className="markdown-body" style={{ backgroundColor: colorBgContainer }}>
                          <Suspense fallback={<LoadingFallback />}>
                            <Markdown>{markdown}</Markdown>
                          </Suspense>
                        </div>
                      ),
                      className: "markdown-body",
                    },
                    {
                      label: inputs,
                      key: "2",
                      children:
                        hclObject && hclObject?.variable ? (
                          <Space orientation="vertical">
                            <h3>Inputs</h3>
                            <span>These variables should be set in the module block when using this module.</span>
                            <table
                              style={{
                                width: "100%",
                                tableLayout: "fixed",
                                whiteSpace: "normal",
                                wordBreak: "break-word",
                                borderCollapse: "collapse",
                                border: `1px solid ${colorBorder}`,
                                backgroundColor: colorBgContainer,
                              }}
                            >
                              <thead>
                                <tr>
                                  <th
                                    style={{
                                      width: "40%",
                                      textAlign: "left",
                                      verticalAlign: "top",
                                      padding: "8px",
                                      border: `1px solid ${colorBorder}`,
                                      backgroundColor: colorFillSecondary,
                                    }}
                                  >
                                    Name
                                  </th>
                                  <th
                                    style={{
                                      width: "20%",
                                      textAlign: "left",
                                      verticalAlign: "top",
                                      padding: "8px",
                                      border: `1px solid ${colorBorder}`,
                                      backgroundColor: colorFillSecondary,
                                    }}
                                  >
                                    Type
                                  </th>
                                  <th
                                    style={{
                                      width: "35%",
                                      textAlign: "left",
                                      verticalAlign: "top",
                                      padding: "8px",
                                      border: `1px solid ${colorBorder}`,
                                      backgroundColor: colorFillSecondary,
                                    }}
                                  >
                                    Description
                                  </th>
                                  <th
                                    style={{
                                      width: "15%",
                                      textAlign: "left",
                                      verticalAlign: "top",
                                      padding: "8px",
                                      border: `1px solid ${colorBorder}`,
                                      backgroundColor: colorFillSecondary,
                                    }}
                                  >
                                    Default
                                  </th>
                                </tr>
                              </thead>
                              <tbody>
                                {Object.keys(hclObject?.variable).map((keyName, i) => (
                                  <tr
                                    key={i}
                                    style={{ backgroundColor: i % 2 === 0 ? colorBgContainer : colorFillTertiary }}
                                  >
                                    <td
                                      style={{
                                        textAlign: "left",
                                        verticalAlign: "top",
                                        padding: "8px",
                                        border: `1px solid ${colorBorder}`,
                                      }}
                                    >
                                      <Typography.Text copyable strong>
                                        {keyName}
                                      </Typography.Text>
                                    </td>
                                    <td
                                      style={{
                                        textAlign: "left",
                                        verticalAlign: "top",
                                        padding: "8px",
                                        border: `1px solid ${colorBorder}`,
                                      }}
                                    >
                                      <span
                                        style={{
                                          padding: "4px 8px",
                                          borderRadius: "4px",
                                          display: "inline-block",
                                        }}
                                      >
                                        {hclObject?.variable[keyName][0]?.type?.replace(/{|}|\$/g, "")}
                                      </span>
                                    </td>
                                    <td
                                      style={{
                                        textAlign: "left",
                                        verticalAlign: "top",
                                        padding: "8px",
                                        border: `1px solid ${colorBorder}`,
                                      }}
                                    >
                                      {JSON.stringify(hclObject?.variable[keyName][0]?.description)?.replaceAll(
                                        '"',
                                        ""
                                      )}
                                    </td>
                                    <td
                                      style={{
                                        textAlign: "left",
                                        verticalAlign: "top",
                                        padding: "8px",
                                        border: `1px solid ${colorBorder}`,
                                      }}
                                    >
                                      {JSON.stringify(hclObject?.variable[keyName][0]?.default)}
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </Space>
                        ) : (
                          <p>{loadingInputs}</p>
                        ),
                    },
                    {
                      label: outputs,
                      key: "3",
                      children:
                        hclObject && hclObject?.output ? (
                          <Space orientation="vertical">
                            <h3>Outputs</h3>
                            <span>These outputs are returned by this module.</span>
                            <table
                              style={{
                                width: "100%",
                                tableLayout: "fixed",
                                whiteSpace: "normal",
                                wordBreak: "break-word",
                                borderCollapse: "collapse",
                                border: `1px solid ${colorBorder}`,
                                backgroundColor: colorBgContainer,
                              }}
                            >
                              <thead>
                                <tr>
                                  <th
                                    style={{
                                      width: "30%",
                                      textAlign: "left",
                                      verticalAlign: "top",
                                      padding: "8px",
                                      border: `1px solid ${colorBorder}`,
                                      backgroundColor: colorFillSecondary,
                                    }}
                                  >
                                    Name
                                  </th>
                                  <th
                                    style={{
                                      width: "70%",
                                      textAlign: "left",
                                      verticalAlign: "top",
                                      padding: "8px",
                                      border: `1px solid ${colorBorder}`,
                                      backgroundColor: colorFillSecondary,
                                    }}
                                  >
                                    Description
                                  </th>
                                </tr>
                              </thead>
                              <tbody>
                                {Object.keys(hclObject?.output).map((keyName, i) => (
                                  <tr
                                    key={i}
                                    style={{ backgroundColor: i % 2 === 0 ? colorBgContainer : colorFillTertiary }}
                                  >
                                    <td
                                      style={{
                                        textAlign: "left",
                                        verticalAlign: "top",
                                        padding: "8px",
                                        border: `1px solid ${colorBorder}`,
                                      }}
                                    >
                                      <Typography.Text copyable strong>
                                        {keyName}
                                      </Typography.Text>
                                    </td>
                                    <td
                                      style={{
                                        textAlign: "left",
                                        verticalAlign: "top",
                                        padding: "8px",
                                        border: `1px solid ${colorBorder}`,
                                      }}
                                    >
                                      {JSON.stringify(hclObject?.output[keyName][0]?.description)?.replaceAll('"', "")}
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </Space>
                        ) : (
                          <p>{loadingOutputs}</p>
                        ),
                    },
                    {
                      label: resources,
                      key: "5",
                      children:
                        hclObject && hclObject?.resource ? (
                          <Space orientation="vertical">
                            <h3>Resources</h3>
                            <span>This is the list of resources this module can create.</span>
                            <span>This module defines {Object.keys(hclObject?.resource)?.length} resources.</span>
                            <ul>
                              {Object.keys(hclObject?.resource).map((resourceType, i) =>
                                Object.keys(hclObject?.resource[resourceType]).map((resourceName, j) => (
                                  <li key={`${i}-${j}`}>
                                    <Tag>
                                      {resourceType}.{resourceName}
                                    </Tag>
                                  </li>
                                ))
                              )}
                            </ul>
                          </Space>
                        ) : (
                          <p>{loadingResources}</p>
                        ),
                    },
                  ]}
                />
              </Space>
            </Col>
            <Col span={8}>
              <Card>
                <Space style={{ paddingRight: "10px", width: "100%" }} orientation="vertical">
                  <div style={{ width: "100%" }}>
                    <Dropdown
                      menu={{
                        items: [
                          ...(permissions.manageModule && selectedVersion
                            ? [
                                {
                                  key: "versionStatus",
                                  label: `Change status of version ${version}…`,
                                  onClick: () => setVersionStatusOpen(true),
                                },
                              ]
                            : []),
                          {
                            key: "delete",
                            label: (
                              <Popconfirm
                                okButtonProps={{ danger: true }}
                                title={
                                  <p>
                                    Module <b>{module.attributes.name}</b> will be permanently deleted.
                                    <br />
                                    Are you sure?
                                  </p>
                                }
                                onConfirm={() => {
                                  onDelete(id!);
                                }}
                                okText="Yes"
                                cancelText="No"
                                placement="left"
                              >
                                <Space>
                                  <DeleteOutlined style={{ color: "#ff4d4f" }} />
                                  <span style={{ color: "#ff4d4f" }}>Remove from organization</span>
                                </Space>
                              </Popconfirm>
                            ),
                          },
                        ],
                      }}
                      trigger={["click"]}
                    >
                      <Button style={{ width: "100%" }} icon={<SettingOutlined />}>
                        Manage Module <DownOutlined />
                      </Button>
                    </Dropdown>
                    <Divider />
                  </div>
                  <p className="moduleSubtitles">Usage Instructions</p>
                  <p className="moduleInstructions">
                    Copy and paste into your Terraform configuration and set values for the input variables.
                  </p>
                  <div style={{ width: "100%" }}>
                    <Divider />
                    <p className="moduleSubtitles">Copy configuration details</p>
                  </div>
                  {selectedVersion?.removed ? (
                    <Typography.Text type="secondary">
                      Version {version} is no longer served by the registry.
                      {upgradeTo ? ` Use version ${upgradeTo} instead.` : ""}
                    </Typography.Text>
                  ) : (
                    <>
                      {selectedVersion?.deprecated && (
                        <Typography.Text type="warning">
                          Version {version} is deprecated.
                          {upgradeTo && upgradeTo !== version ? ` Consider version ${upgradeTo}.` : ""}
                        </Typography.Text>
                      )}
                      <pre className="moduleCode">
                        module &quot;{module.attributes.name}&quot; {"{"}
                        <br />
                        &nbsp;&nbsp;source = &quot;{new URL(window._env_.REACT_APP_REGISTRY_URI).hostname}/
                        {module.attributes.registryPath}
                        {submodulePath}&quot;
                        <br />
                        &nbsp;&nbsp;version = &quot;{version}&quot;
                        <br />
                        &nbsp;&nbsp;# insert required variables here
                        <br />
                        {"}"}
                      </pre>
                    </>
                  )}
                  <Tag style={{ width: "100%", fontSize: "13px" }} color="blue">
                    <div style={{ whiteSpace: "normal", wordWrap: "break-word" }}>
                      When running Terraform on the CLI, you must configure credentials in .terraformrc or terraform.rc
                      to access this module:
                    </div>
                    <pre className="moduleCredentials">
                      credentials &quot;{new URL(window._env_.REACT_APP_REGISTRY_URI).hostname}&quot; {" {"}
                      <br />
                      &nbsp;&nbsp;token = &quot;xxxxxx.yyyyyy.zzzzzzzzzzzzz&quot;
                      <br />
                      {"}"}
                    </pre>
                  </Tag>
                </Space>
              </Card>
            </Col>
          </Row>
        </div>
      )}
      {selectedVersion && (
        <VersionStatusModal
          open={versionStatusOpen}
          version={selectedVersion.version}
          kind="module"
          status={selectedVersion}
          onCancel={() => setVersionStatusOpen(false)}
          onSave={saveVersionStatus}
        />
      )}
    </PageWrapper>
  );
};

function fixSshURL(source: string | undefined): string {
  if (!source) return "";
  if (source.startsWith("git@")) {
    return source.replace(":", "/").replace("git@", "https://");
  } else {
    return source;
  }
}

function setModuleInclude(
  includes: any[],
  setVCSProvider: React.Dispatch<React.SetStateAction<VcsType | undefined>>,
  setAllVersions: React.Dispatch<React.SetStateAction<ModuleVersionItem[]>>
): ModuleVersionItem[] {
  const versions: ModuleVersionItem[] = [];
  (includes ?? []).forEach((element: any) => {
    if (element.type === "vcs") {
      setVCSProvider(element.attributes.vcsType);
    }
    if (element.type === "module_version") {
      versions.push({ id: element.id, ...element.attributes });
    }
  });
  setAllVersions(versions);
  return versions;
}

// latestVersion is only recalculated by the module refresh job, so it can still point at a version
// that was removed since then. Show the recommended version that is still served instead.
function defaultVersion(latestVersion: string, versions: ModuleVersionItem[]): string {
  if (!versions.find((v) => v.version === latestVersion)?.removed) return latestVersion;
  const newestFirst = [...versions].sort((a, b) => compareVersions(b.version, a.version));
  return recommendedVersion(newestFirst)?.version ?? latestVersion;
}
