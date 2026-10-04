import { CopyOutlined, DeleteOutlined, DownOutlined, SettingOutlined } from "@ant-design/icons";
import { Button, Dropdown, message, Select, Spin, Table, Tabs, Tooltip, Typography } from "antd";
import { lazy, Suspense, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import LoadingFallback from "@/components/feedback/LoadingFallback";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { copyValue } from "@/components/settings/IdField/IdField";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { ModuleModel, ModuleVersionAttributes, VcsType } from "../types";
import { compareVersions } from "../Workspaces/Workspaces";
import { providerIcon, registryHostname } from "./registryHelpers";
import "./Module.css";
import VcsLogo from "@/components/display/VcsLogo";
import { relativeTime } from "@/modules/utils/dates";
import { formatCount } from "@/modules/utils/formatCount";
import VersionStatusModal, {
  recommendedVersion,
  VersionStatus,
  VersionStatusAlert,
  versionStatusChangeMessage,
  versionStatusSuffix,
} from "@/components/modals/VersionStatusModal";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

type ModuleDetails = {
  submodules: string[];
  variables: { name: string; type: string | null; description: string | null; defaultValue: string | null }[];
  outputs: { name: string; description: string | null }[];
  resources: { type: string; name: string }[];
  readme: string | null;
};

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

type DetailsState = "loading" | "ready" | "failed" | "removed";

const UNAVAILABLE: Record<Exclude<DetailsState, "ready">, React.ReactNode> = {
  loading: <Spin />,
  failed: "The module details could not be loaded.",
  removed: "Not available because this version was removed from the registry.",
};

const machineValue = (value: string | null) =>
  value ? <code className="registry-mono registry-pre">{value}</code> : <span aria-label="none">—</span>;

export const ModuleDetails = ({ organizationName }: Props) => {
  const { orgid, id } = useParams<Params>();
  const [module, setModule] = useState<ModuleModel>();
  const [moduleName, setModuleName] = useState("...");
  const [version, setVersion] = useState("...");
  const [allVersions, setAllVersions] = useState<ModuleVersionItem[]>([]);
  const [versionStatusOpen, setVersionStatusOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [vcsProvider, setVCSProvider] = useState<VcsType>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [details, setDetails] = useState<ModuleDetails | null>(null);
  const [detailsState, setDetailsState] = useState<DetailsState>("loading");
  const [submodules, setSubmodules] = useState<string[]>([]);
  const [submodule, setSubmodule] = useState("");
  const navigate = useNavigate();
  const { permissions } = useOrgPermissions();

  const selectedVersion = allVersions.find((v) => v.version === version);
  const versionsNewestFirst = [...allVersions].sort((a, b) => compareVersions(b.version, a.version));
  const upgradeTo = recommendedVersion(versionsNewestFirst)?.version;
  // Removed versions go last, as in the version menus elsewhere.
  const versionOptions = [
    ...versionsNewestFirst.filter((v) => v.status !== "removed"),
    ...versionsNewestFirst.filter((v) => v.status === "removed"),
  ].map((v) => ({ value: v.version, label: v.version + versionStatusSuffix(v) }));

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
    setSubmodule("");
    if (versions.find((v) => v.version === selected)?.status === "removed") {
      setDetails(null);
      setSubmodules([]);
      setDetailsState("removed");
      return;
    }
    loadModuleDetails(path, selected);
  };

  const onSelectSubmodule = (name: string) => {
    setSubmodule(name);
    loadModuleDetails(module!.attributes.registryPath, version, name);
  };

  const onDelete = () => {
    axiosInstance
      .delete(`organization/${orgid}/module/${id}`)
      .then(() => {
        message.success(`Module ${moduleName} deleted`);
        navigate(`/organizations/${orgid}/registry`);
      })
      .catch((error) => {
        message.error("Failed to delete module: " + getErrorMessage(error));
      });
  };

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

  // The registry unpacks the module archive and parses the HCL; the browser only renders the result.
  const loadModuleDetails = (path: string, version: string, submodule = "") => {
    setDetails(null);
    setDetailsState("loading");
    axiosInstance
      .get<ModuleDetails>(`${window._env_.REACT_APP_REGISTRY_URI}/terraform/modules/v1/${path}/${version}/details`, {
        params: submodule ? { submodule } : undefined,
      })
      .then((resp) => {
        setDetails(resp.data);
        setDetailsState("ready");
        if (submodule === "") setSubmodules(resp.data.submodules);
      })
      .catch((err) => {
        console.error("Error loading module details:", err);
        setDetailsState("failed");
      });
  };

  const registryHost = registryHostname();
  const attributes = module?.attributes;
  const address = attributes
    ? `${registryHost}/${attributes.registryPath}${submodule ? `//modules/${submodule}` : ""}`
    : "";
  const snippet = `module "${submodule || moduleName}" {
  source  = "${address}"
  version = "${version}"

  # Set the required inputs here.
}`;
  const credentialsSnippet = `credentials "${registryHost}" {
  token = "<your API token>"
}`;
  const sourceUrl = fixSshURL(attributes?.source);
  const sourcePath = repositoryPath(sourceUrl);

  const copy = (text: string, what: string) =>
    copyValue(text).then(
      () => message.success(`${what} copied`),
      () => message.error(`Could not copy the ${what.toLowerCase()}`)
    );

  const tabContent = (render: (d: ModuleDetails) => React.ReactNode) =>
    detailsState === "ready" && details ? (
      render(details)
    ) : (
      <span className="registry-tab-empty">{UNAVAILABLE[detailsState as Exclude<DetailsState, "ready">]}</span>
    );

  const count = (label: string, items?: unknown[]) => (items?.length ? `${label} (${items.length})` : label);

  return (
    <PageWrapper
      title={moduleName}
      showTitle={false}
      loading={loading}
      loadingText="Loading module..."
      error={error ? { title: error.includes("permission") ? "Access denied" : "Error", message: error } : undefined}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
        { label: moduleName, path: `/organizations/${orgid}/registry/${id}` },
      ]}
      width="reading"
    >
      {module && attributes && (
        <div className="registry-detail">
          <header className="registry-detail-header">
            <span className="registry-detail-logo" aria-hidden="true">
              {providerIcon(attributes.provider)}
            </span>
            <div className="registry-detail-title">
              <Typography.Title level={2}>{moduleName}</Typography.Title>
              <span className="registry-detail-address">
                <code>{address}</code>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  aria-label="Copy module address"
                  onClick={() => copy(address, "Module address")}
                />
              </span>
            </div>
            <div className="registry-detail-actions">
              <Select
                aria-label="Version"
                className="registry-detail-version"
                value={version}
                onChange={(v) => showVersion(attributes.registryPath, v, allVersions)}
                options={versionOptions}
                showSearch
              />
              <Dropdown
                trigger={["click"]}
                menu={{
                  items: [
                    ...(permissions.manageModule && selectedVersion
                      ? [
                          {
                            key: "versionStatus",
                            label: `Change status of version ${version}…`,
                            onClick: () => setVersionStatusOpen(true),
                          },
                          { type: "divider" as const },
                        ]
                      : []),
                    {
                      key: "delete",
                      danger: true,
                      icon: <DeleteOutlined />,
                      disabled: !permissions.manageModule,
                      label: permissions.manageModule ? (
                        "Delete module"
                      ) : (
                        <Tooltip title="You need the Manage modules permission to delete this module." placement="left">
                          <span>Delete module</span>
                        </Tooltip>
                      ),
                      onClick: () => setDeleteOpen(true),
                    },
                  ],
                }}
              >
                <Button icon={<SettingOutlined />}>
                  Manage module <DownOutlined />
                </Button>
              </Dropdown>
            </div>
          </header>

          {attributes.description && (
            <Typography.Paragraph type="secondary" className="registry-detail-description">
              {attributes.description}
            </Typography.Paragraph>
          )}

          {selectedVersion && (
            <div className="registry-detail-alert">
              <VersionStatusAlert version={version} status={selectedVersion} upgradeTo={upgradeTo} />
            </div>
          )}

          <div className="registry-detail-layout">
            <div className="registry-detail-main">
              <section className="registry-section" aria-labelledby="module-usage">
                <Typography.Title level={3} id="module-usage">
                  Usage
                </Typography.Title>
                {selectedVersion?.status === "removed" ? (
                  <Typography.Paragraph type="secondary">
                    Version {version} is no longer served by the registry.
                    {upgradeTo ? ` Use version ${upgradeTo} instead.` : ""}
                  </Typography.Paragraph>
                ) : (
                  <>
                    <Typography.Paragraph type="secondary">
                      Add this block to your configuration and set the module&apos;s inputs.
                      {selectedVersion?.status === "deprecated" && upgradeTo && upgradeTo !== version
                        ? ` Version ${version} is deprecated; consider version ${upgradeTo}.`
                        : ""}
                    </Typography.Paragraph>
                    <pre className="registry-snippet">{snippet}</pre>
                    <Button type="primary" icon={<CopyOutlined />} onClick={() => copy(snippet, "Configuration")}>
                      Copy configuration
                    </Button>
                    <details className="registry-hint">
                      <summary>Using the module from the CLI</summary>
                      <p>
                        Add an API token for {registryHost} to your CLI configuration file (.terraformrc or
                        terraform.rc):
                      </p>
                      <pre className="registry-snippet registry-snippet-small">{credentialsSnippet}</pre>
                    </details>
                  </>
                )}
              </section>

              <section className="registry-section">
                <Tabs
                  className="registry-tabs"
                  defaultActiveKey="readme"
                  tabBarExtraContent={
                    submodules.length > 0 && (
                      <Select
                        aria-label="Submodule"
                        className="registry-submodule"
                        value={submodule}
                        onChange={onSelectSubmodule}
                        options={[
                          { value: "", label: "Root module" },
                          ...submodules.map((name) => ({ value: name, label: `modules/${name}` })),
                        ]}
                        showSearch
                      />
                    )
                  }
                  items={[
                    {
                      key: "readme",
                      label: "Readme",
                      children: tabContent((d) =>
                        d.readme ? (
                          <div className="registry-readme">
                            <Suspense fallback={<LoadingFallback />}>
                              <Markdown>{d.readme}</Markdown>
                            </Suspense>
                          </div>
                        ) : (
                          <span className="registry-tab-empty">This module has no README.</span>
                        )
                      ),
                    },
                    {
                      key: "inputs",
                      label: count("Inputs", details?.variables),
                      children: tabContent((d) =>
                        d.variables.length ? (
                          <>
                            <p className="registry-tab-intro">Set these variables in the module block.</p>
                            <Table
                              className="registry-table"
                              size="small"
                              tableLayout="fixed"
                              rowKey="name"
                              dataSource={d.variables}
                              pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
                              columns={[
                                {
                                  title: "Name",
                                  dataIndex: "name",
                                  width: "28%",
                                  render: (name: string) => (
                                    <Typography.Text copyable className="registry-mono">
                                      {name}
                                    </Typography.Text>
                                  ),
                                },
                                { title: "Type", dataIndex: "type", width: "20%", render: machineValue },
                                { title: "Description", dataIndex: "description" },
                                { title: "Default", dataIndex: "defaultValue", width: "20%", render: machineValue },
                              ]}
                            />
                          </>
                        ) : (
                          <span className="registry-tab-empty">This module has no inputs.</span>
                        )
                      ),
                    },
                    {
                      key: "outputs",
                      label: count("Outputs", details?.outputs),
                      children: tabContent((d) =>
                        d.outputs.length ? (
                          <>
                            <p className="registry-tab-intro">This module returns these outputs.</p>
                            <Table
                              className="registry-table"
                              size="small"
                              tableLayout="fixed"
                              rowKey="name"
                              dataSource={d.outputs}
                              pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
                              columns={[
                                {
                                  title: "Name",
                                  dataIndex: "name",
                                  width: "35%",
                                  render: (name: string) => (
                                    <Typography.Text copyable className="registry-mono">
                                      {name}
                                    </Typography.Text>
                                  ),
                                },
                                { title: "Description", dataIndex: "description" },
                              ]}
                            />
                          </>
                        ) : (
                          <span className="registry-tab-empty">This module has no outputs.</span>
                        )
                      ),
                    },
                    {
                      key: "resources",
                      label: count("Resources", details?.resources),
                      children: tabContent((d) =>
                        d.resources.length ? (
                          <>
                            <p className="registry-tab-intro">This module can create these resources.</p>
                            <Table
                              className="registry-table"
                              size="small"
                              tableLayout="fixed"
                              rowKey={(r) => `${r.type}.${r.name}`}
                              dataSource={d.resources}
                              pagination={{ pageSize: 50, hideOnSinglePage: true, showSizeChanger: false }}
                              columns={[
                                { title: "Type", dataIndex: "type", render: machineValue },
                                { title: "Name", dataIndex: "name", render: machineValue },
                              ]}
                            />
                          </>
                        ) : (
                          <span className="registry-tab-empty">This module defines no resources.</span>
                        )
                      ),
                    },
                  ]}
                />
              </section>
            </div>

            <aside className="registry-rail" aria-label="Module details">
              <ul className="registry-rail-facts">
                <li>
                  <span className="registry-rail-label">Version</span>
                  <code>{version}</code>
                </li>
                {selectedVersion?.gitTag && (
                  <li>
                    <span className="registry-rail-label">Git tag</span>
                    <code>{selectedVersion.gitTag}</code>
                  </li>
                )}
                {selectedVersion?.commit && (
                  <li>
                    <span className="registry-rail-label">Commit</span>
                    <code title={selectedVersion.commit}>{selectedVersion.commit.slice(0, 12)}</code>
                  </li>
                )}
                <li>
                  <span className="registry-rail-label">Provider</span>
                  <code>{attributes.provider}</code>
                </li>
                {attributes.source && (
                  <li>
                    <span className="registry-rail-label">Source</span>
                    <span className="registry-rail-source">
                      <VcsLogo type={vcsProvider} />
                      {sourcePath ? (
                        <a href={sourceUrl} target="_blank" rel="noopener noreferrer">
                          {sourcePath}
                        </a>
                      ) : (
                        <code>{attributes.source}</code>
                      )}
                    </span>
                  </li>
                )}
                {attributes.folder && (
                  <li>
                    <span className="registry-rail-label">Folder</span>
                    <code>{attributes.folder}</code>
                  </li>
                )}
                {attributes.tagPrefix && (
                  <li>
                    <span className="registry-rail-label">Tag prefix</span>
                    <code>{attributes.tagPrefix}</code>
                  </li>
                )}
                <li>
                  <span className="registry-rail-label">Downloads</span>
                  <span>{formatCount(attributes.downloadQuantity ?? 0)}</span>
                </li>
                <li>
                  <span className="registry-rail-label">Versions</span>
                  <span>{allVersions.length}</span>
                </li>
                {(attributes.updatedDate || attributes.createdDate) && (
                  <li>
                    <span className="registry-rail-label">{attributes.updatedDate ? "Updated" : "Published"}</span>
                    <span title={attributes.updatedDate ?? attributes.createdDate}>
                      {relativeTime(attributes.updatedDate ?? attributes.createdDate)}
                    </span>
                  </li>
                )}
              </ul>
            </aside>
          </div>
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
      <DeleteConfirmationModal
        open={deleteOpen}
        title="Delete module"
        okText="Delete module"
        confirmValue={moduleName}
        message={
          <>
            This permanently deletes the <strong>{moduleName}</strong> module and all {allVersions.length} of its
            versions from the {organizationName} registry. Configurations that use <code>{address}</code> fail on their
            next init. This cannot be undone.
          </>
        }
        onConfirm={() => {
          setDeleteOpen(false);
          onDelete();
        }}
        onCancel={() => setDeleteOpen(false)}
      />
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

// "https://github.com/owner/repo.git" -> "owner/repo"; empty when the source is not a URL.
function repositoryPath(url: string): string {
  try {
    return new URL(url).pathname.replace(/\.git$/, "").substring(1);
  } catch {
    return "";
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
  if (versions.find((v) => v.version === latestVersion)?.status !== "removed") return latestVersion;
  const newestFirst = [...versions].sort((a, b) => compareVersions(b.version, a.version));
  return recommendedVersion(newestFirst)?.version ?? latestVersion;
}
