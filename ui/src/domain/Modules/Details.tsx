import {
  ArrowLeftOutlined,
  ClockCircleOutlined,
  CloudOutlined,
  DeleteOutlined,
  DownloadOutlined,
  DownOutlined,
  GithubOutlined,
  GitlabOutlined,
} from "@ant-design/icons";
import {
  Breadcrumb,
  Button,
  Card,
  Col,
  Divider,
  Dropdown,
  Layout,
  Menu,
  message,
  Popconfirm,
  Row,
  Space,
  Spin,
  Tabs,
  Tag,
  theme,
  Typography,
} from "antd";
import { Buffer } from "buffer";
import * as hcl from "hcl2-parser";
import { DateTime } from "luxon";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { BiBookBookmark } from "react-icons/bi";
import { FaAws } from "react-icons/fa";
import { MdBusiness } from "react-icons/md";
import { RiFolderHistoryLine } from "react-icons/ri";
import { SiBitbucket } from "react-icons/si";
import { VscAzure, VscAzureDevops } from "react-icons/vsc";
import Markdown from "react-markdown";
import { Link, useNavigate, useParams } from "react-router-dom";
import remarkGfm from "remark-gfm";
import { unzip } from "unzipit";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { ModuleModel, ModuleVersionAttributes, VcsType } from "../types";
import { compareVersions } from "../Workspaces/Workspaces";
import "./Module.css";
const { Content } = Layout;

type Props = {
  organizationName: string;
};

type Params = {
  orgid: string;
  id: string;
};

export const ModuleDetails = ({ organizationName }: Props) => {
  const { orgid, id } = useParams<Params>();
  const [module, setModule] = useState<ModuleModel>();
  const [moduleName, setModuleName] = useState("...");
  const [version, setVersion] = useState("...");
  const [allVersions, setAllVersions] = useState<ModuleVersionAttributes[]>([]);
  const [vcsProvider, setVCSProvider] = useState<VcsType>();
  const [loading, setLoading] = useState(false);
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

  const handleClick = (e: { key: string }) => {
    setMarkdown("loading...");
    setVersion(e.key);
    if (module) {
      loadReadme(module.attributes.registryPath, e.key);
      loadModuleDetails(module.attributes.registryPath, e.key);
    } else {
      setMarkdown("Failed to load module");
    }
  };

  // if submodule is empty then load the submodules dropdown list
  // if submodule is empty then load the root module
  // if submodule is not empty then load the specific submodule tf files
  async function readFiles(url: string, submodule = "") {
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
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid!);
    axiosInstance.get(`organization/${orgid}/module/${id}?include=vcs,version`).then((response) => {
      setModule(response.data.data);
      setLoading(false);
      setModuleName(response.data.data.attributes.name);
      const latestVersion = response.data.data.attributes.latestVersion;
      setVersion(latestVersion);
      loadReadme(response.data.data.attributes.registryPath, latestVersion);
      loadModuleDetails(response.data.data.attributes.registryPath, latestVersion);
      setModuleInclude(response.data.included, setVCSProvider, setAllVersions);
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
      });
  };

  const loadReadme = (path: string, version: string) => {
    axiosInstance
      .get(`${window._env_.REACT_APP_REGISTRY_URI}/terraform/readme/v1/${path}/${version}/download`)
      .then((resp) => {
        loadReadmeFile(resp.data.content);
      });
  };

  const renderVCSLogo = (vcs?: VcsType) => {
    switch (vcs) {
      case "GITLAB":
        return <GitlabOutlined style={{ fontSize: "18px" }} />;
      case "BITBUCKET":
        return (
          <IconContext.Provider value={{ size: "18px" }}>
            <SiBitbucket />
            &nbsp;
          </IconContext.Provider>
        );
      case "AZURE_DEVOPS":
        return (
          <IconContext.Provider value={{ size: "18px" }}>
            <VscAzureDevops />
            &nbsp;
          </IconContext.Provider>
        );
      default:
        return <GithubOutlined style={{ fontSize: "18px" }} />;
    }
  };

  return (
    <Content style={{ padding: "0 50px" }}>
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: organizationName,
          },
          {
            title: <Link to={`/organizations/${orgid}/registry`}>Modules</Link>,
          },
          {
            title: moduleName,
          },
        ]}
      />

      <div className="site-layout-content" style={{ background: colorBgContainer }}>
        {loading || !module ? (
          <Spin spinning={loading} tip="Loading Module...">
            <p style={{ marginTop: "50px" }}></p>
          </Spin>
        ) : (
          <div>
            <Row>
              <Col span={17}>
                <Space direction="vertical" style={{ marginTop: "10px", width: "95%" }}>
                  {submodule === "" && (
                    <>
                      <Tag color="blue">
                        <span>
                          <MdBusiness /> Private
                        </span>
                      </Tag>
                      <div>
                        <h2 className="moduleTitle">{module.attributes.name}</h2>
                        <span className="moduleDescription">{module.attributes.description}</span>
                      </div>
                      <Space className="moduleProvider" size="large" direction="horizontal">
                        <Typography.Text type="secondary">Published by {organizationName}</Typography.Text>
                        <Typography.Text type="secondary">
                          Provider {renderLogo(module.attributes.provider)} {module.attributes.provider}
                        </Typography.Text>
                      </Space>
                      <IconContext.Provider value={{ size: "1.3em" }}>
                        <table className="moduleDetails">
                          <tbody>
                            <tr>
                              <td>
                                <Typography.Text type="secondary">
                                  <RiFolderHistoryLine /> Version
                                </Typography.Text>
                              </td>
                              <td>
                                <Typography.Text type="secondary">
                                  <ClockCircleOutlined /> Published
                                </Typography.Text>
                              </td>
                              <td>
                                <Typography.Text type="secondary">
                                  <DownloadOutlined /> Provisions
                                </Typography.Text>
                              </td>
                              <td>
                                <Typography.Text type="secondary">
                                  <BiBookBookmark /> Source
                                </Typography.Text>
                              </td>
                            </tr>
                            <tr className="black">
                              <td>
                                <Typography.Text>
                                  {version}{" "}
                                  <Dropdown
                                    menu={{
                                      items: allVersions
                                        .sort((a, b) => compareVersions(a.version, b.version))
                                        .reverse()
                                        .map((v) => ({ key: v.version.replaceAll(".", ""), label: v.version })),
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
                                        color: "#1677ff",
                                        cursor: "pointer",
                                      }}
                                    >
                                      Change <DownOutlined />
                                    </button>
                                  </Dropdown>
                                </Typography.Text>
                              </td>
                              <td>
                                <Typography.Text>
                                  {DateTime.fromISO(module.attributes.createdDate ?? "").toRelative()}
                                </Typography.Text>
                              </td>
                              <td>
                                <Typography.Text>&nbsp; {module.attributes.downloadQuantity}</Typography.Text>
                              </td>
                              <td>
                                <Typography.Text>
                                  {renderVCSLogo(vcsProvider)}{" "}
                                  {module.attributes.source && (
                                    <a
                                      href={fixSshURL(module.attributes.source)}
                                      target="_blank"
                                      rel="noopener noreferrer"
                                    >
                                      {new URL(fixSshURL(module.attributes.source)).pathname
                                        .replace(".git", "")
                                        .substring(1)}
                                    </a>
                                  )}
                                </Typography.Text>
                              </td>
                            </tr>
                          </tbody>
                        </table>
                      </IconContext.Provider>
                      {submodules.length > 0 && (
                        <Dropdown
                          menu={{
                            items: submodules.map((name) => ({ key: name, label: name })),
                            onClick: onClickSubmodule,
                          }}
                          trigger={["click"]}
                        >
                          <Button style={{ marginTop: "20px", fontSize: ".75rem" }}>
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
                            <Markdown remarkPlugins={[remarkGfm]}>{markdown}</Markdown>
                          </div>
                        ),
                        className: "markdown-body",
                      },
                      {
                        label: inputs,
                        key: "2",
                        children:
                          hclObject && hclObject?.variable ? (
                            <Space direction="vertical">
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
                            <Space direction="vertical">
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
                                        {JSON.stringify(hclObject?.output[keyName][0]?.description)?.replaceAll(
                                          '"',
                                          ""
                                        )}
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
                            <Space direction="vertical">
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
              <Col span={7}>
                <Card>
                  <Space style={{ paddingRight: "10px", width: "100%" }} direction="vertical">
                    <div style={{ width: "100%" }}>
                      <Popconfirm
                        onConfirm={() => {
                          onDelete(id!);
                        }}
                        style={{ width: "100%" }}
                        title={
                          <p>
                            Module <b>{module.attributes.name}</b> will be permanently deleted from this organization.
                            <br />
                            Are you sure?
                          </p>
                        }
                        okText="Yes"
                        cancelText="No"
                        placement="bottom"
                      >
                        <Button type="default" danger style={{ width: "100%" }}>
                          <Space>
                            <DeleteOutlined />
                            Delete Module
                          </Space>
                        </Button>
                      </Popconfirm>
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
                    <Tag style={{ width: "100%", fontSize: "13px" }} color="blue">
                      <div style={{ whiteSpace: "normal", wordWrap: "break-word" }}>
                        When running Terraform on the CLI, you must configure credentials in .terraformrc or
                        terraform.rc to access this module:
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
              <Col span={1}></Col>
            </Row>
          </div>
        )
        }
      </div >
    </Content >
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
  setAllVersions: React.Dispatch<React.SetStateAction<ModuleVersionAttributes[]>>
) {
  const versions: ModuleVersionAttributes[] = [];
  includes.forEach((element: any) => {
    if (element.type === "vcs") {
      setVCSProvider(element.attributes.vcsType);
    }
    if (element.type === "module_version") {
      versions.push(element.attributes);
    }
  });
  setAllVersions(versions);
}
