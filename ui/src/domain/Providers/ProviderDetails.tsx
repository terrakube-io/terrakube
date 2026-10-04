import {
  CloudServerOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  ExportOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { Button, Dropdown, message, Select, Tooltip, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { copyValue } from "@/components/settings/IdField/IdField";
import { getErrorMessage } from "@/config/axiosConfig";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import VersionStatusModal, {
  recommendedVersion,
  VersionStatus,
  VersionStatusAlert,
  versionStatusChangeMessage,
  versionStatusSuffix,
} from "@/components/modals/VersionStatusModal";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { parseProviderDescription, registryHostname } from "../Modules/registryHelpers";
import { compareVersions } from "../Workspaces/Workspaces";
import { getProvider, deleteProviderCascade, updateVersionStatus } from "./providerService";
import { ProviderModel, ProviderVersionModel } from "./types";
import "../Modules/Module.css";

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
} & VersionStatus;

export const ProviderDetails = ({ organizationName }: Props) => {
  const { orgid, providerid } = useParams<Params>();
  const navigate = useNavigate();
  const [provider, setProvider] = useState<ProviderModel | null>(null);
  const [versions, setVersions] = useState<VersionInfo[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [deleting, setDeleting] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [versionStatusOpen, setVersionStatusOpen] = useState(false);
  const { permissions } = useOrgPermissions();

  // Load provider data
  useEffect(() => {
    if (!orgid || !providerid) return;
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid);

    setLoading(true);
    setError(undefined);
    getProvider(orgid, providerid)
      .then((response) => {
        setProvider(response.data);

        const versionList: VersionInfo[] = [];
        if (response.included) {
          response.included.forEach((item) => {
            if (item.type === "version") {
              const v = item as ProviderVersionModel;
              versionList.push({ id: v.id, ...v.attributes });
            }
          });
        }
        versionList.sort((a, b) => compareVersions(b.versionNumber, a.versionNumber));
        setVersions(versionList);
        const initial = recommendedVersion(versionList) ?? versionList[0];
        if (initial) {
          setSelectedVersion(initial.versionNumber);
        }
      })
      .catch((error) => {
        console.error("Error loading provider:", error);
        setError("Failed to load provider details: " + getErrorMessage(error));
      })
      .finally(() => setLoading(false));
  }, [orgid, providerid]);

  const providerName = provider?.attributes.name || "";
  const namespace = provider?.attributes.registryNamespace || "";
  const { text: description, source } = parseProviderDescription(provider?.attributes.description);

  const handleDelete = useCallback(() => {
    if (!orgid || !providerid) return;
    setDeleting(true);
    deleteProviderCascade(orgid, providerid)
      .then(() => {
        message.success(`Provider ${providerName} deleted`);
        navigate(`/organizations/${orgid}/registry?tab=providers`);
      })
      .catch((error) => {
        console.error("Error deleting provider:", error);
        message.error("Failed to delete provider: " + (error?.message || "Unknown error"));
      })
      .finally(() => setDeleting(false));
  }, [orgid, providerid, providerName, navigate]);

  const selected = versions.find((v) => v.versionNumber === selectedVersion);
  const upgradeTo = recommendedVersion(versions)?.versionNumber;
  // Removed versions go last, as in the version menus elsewhere.
  const versionOptions = [
    ...versions.filter((v) => v.status !== "removed"),
    ...versions.filter((v) => v.status === "removed"),
  ].map((v) => ({ value: v.versionNumber, label: v.versionNumber + versionStatusSuffix(v) }));

  const saveVersionStatus = async (status: Required<VersionStatus>) => {
    if (!orgid || !providerid || !selected) return;
    try {
      await updateVersionStatus(orgid, providerid, selected.id, status);
      setVersions((list) => list.map((v) => (v.id === selected.id ? { ...v, ...status } : v)));
      setVersionStatusOpen(false);
      message.success(versionStatusChangeMessage(selected.versionNumber, status, "provider"));
    } catch (error) {
      message.error("Failed to update version: " + getErrorMessage(error));
    }
  };

  const address = `${registryHostname()}/${organizationName.toLowerCase()}/${providerName}`;
  const snippet = `terraform {
  required_providers {
    ${providerName} = {
      source  = "${address}"${selectedVersion ? `\n      version = "${selectedVersion}"` : ""}
    }
  }
}`;

  const copy = (text: string, what: string) =>
    copyValue(text).then(
      () => message.success(`${what} copied`),
      () => message.error(`Could not copy the ${what.toLowerCase()}`)
    );

  return (
    <PageWrapper
      title={providerName || "Provider"}
      showTitle={false}
      loading={loading}
      loadingText="Loading provider..."
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
        { label: "Providers", path: `/organizations/${orgid}/registry?tab=providers` },
        { label: providerName || "...", path: `/organizations/${orgid}/registry/providers/${providerid}` },
      ]}
      width="reading"
    >
      {provider && (
        <div className="registry-detail">
          <header className="registry-detail-header">
            <span className="registry-detail-logo" aria-hidden="true">
              <CloudServerOutlined />
            </span>
            <div className="registry-detail-title">
              <Typography.Title level={2}>{providerName}</Typography.Title>
              <span className="registry-detail-address">
                <code>{address}</code>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  aria-label="Copy provider address"
                  onClick={() => copy(address, "Provider address")}
                />
              </span>
            </div>
            <div className="registry-detail-actions">
              {versions.length > 0 && (
                <Select
                  aria-label="Version"
                  className="registry-detail-version"
                  value={selectedVersion}
                  onChange={setSelectedVersion}
                  options={versionOptions}
                  showSearch
                />
              )}
              <Dropdown
                trigger={["click"]}
                menu={{
                  items: [
                    ...(permissions.manageProvider && selected
                      ? [
                          {
                            key: "versionStatus",
                            label: `Change status of version ${selectedVersion}…`,
                            onClick: () => setVersionStatusOpen(true),
                          },
                          { type: "divider" as const },
                        ]
                      : []),
                    {
                      key: "delete",
                      danger: true,
                      icon: <DeleteOutlined />,
                      disabled: !permissions.manageProvider,
                      label: permissions.manageProvider ? (
                        "Delete provider"
                      ) : (
                        <Tooltip
                          title="You need the Manage providers permission to delete this provider."
                          placement="left"
                        >
                          <span>Delete provider</span>
                        </Tooltip>
                      ),
                      onClick: () => setDeleteOpen(true),
                    },
                  ],
                }}
              >
                <Button icon={<SettingOutlined />} loading={deleting}>
                  Manage provider <DownOutlined />
                </Button>
              </Dropdown>
            </div>
          </header>

          {description && (
            <Typography.Paragraph type="secondary" className="registry-detail-description">
              {description}
            </Typography.Paragraph>
          )}

          {selected && (
            <div className="registry-detail-alert">
              <VersionStatusAlert version={selected.versionNumber} status={selected} upgradeTo={upgradeTo} />
            </div>
          )}

          <div className="registry-detail-layout">
            <div className="registry-detail-main">
              <section className="registry-section" aria-labelledby="provider-usage">
                <Typography.Title level={3} id="provider-usage">
                  Usage
                </Typography.Title>
                {selected?.status === "removed" ? (
                  <Typography.Paragraph type="secondary">
                    Version {selected.versionNumber} is no longer served by the registry.
                    {upgradeTo ? ` Use version ${upgradeTo} instead.` : ""}
                  </Typography.Paragraph>
                ) : (
                  <>
                    <Typography.Paragraph type="secondary">
                      Add this block to your configuration and run <code className="registry-mono">terraform init</code>
                      .
                      {selected?.status === "deprecated" && upgradeTo && upgradeTo !== selected.versionNumber
                        ? ` Version ${selected.versionNumber} is deprecated; consider version ${upgradeTo}.`
                        : ""}
                    </Typography.Paragraph>
                    <pre className="registry-snippet">{snippet}</pre>
                    <Button type="primary" icon={<CopyOutlined />} onClick={() => copy(snippet, "Configuration")}>
                      Copy configuration
                    </Button>
                  </>
                )}
              </section>
            </div>

            <aside className="registry-rail" aria-label="Provider details">
              <ul className="registry-rail-facts">
                {selected && (
                  <li>
                    <span className="registry-rail-label">Version</span>
                    <code>{selected.versionNumber}</code>
                  </li>
                )}
                {selected?.protocols && (
                  <li>
                    <span className="registry-rail-label">Protocols</span>
                    <code>{selected.protocols}</code>
                  </li>
                )}
                {namespace && (
                  <li>
                    <span className="registry-rail-label">Namespace</span>
                    <span>{namespace}</span>
                  </li>
                )}
                {source && (
                  <li>
                    <span className="registry-rail-label">Source</span>
                    <a href={source.url} target="_blank" rel="noopener noreferrer">
                      {source.label}
                    </a>
                  </li>
                )}
                <li>
                  <span className="registry-rail-label">Versions</span>
                  <span>{versions.length}</span>
                </li>
              </ul>
              <section className="registry-rail-section" aria-labelledby="provider-links">
                <Typography.Title level={3} id="provider-links">
                  Helpful links
                </Typography.Title>
                <ul className="registry-rail-links">
                  {namespace && providerName && (
                    <li>
                      <a
                        href={`https://registry.terraform.io/providers/${namespace}/${providerName}/latest/docs`}
                        target="_blank"
                        rel="noopener noreferrer"
                      >
                        Provider documentation <ExportOutlined aria-label="opens in a new tab" />
                      </a>
                    </li>
                  )}
                  <li>
                    <a
                      href="https://www.terraform.io/docs/language/providers/configuration.html"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      Using providers <ExportOutlined aria-label="opens in a new tab" />
                    </a>
                  </li>
                  {source && (
                    <li>
                      <a href={`${source.url}/issues`} target="_blank" rel="noopener noreferrer">
                        Report an issue <ExportOutlined aria-label="opens in a new tab" />
                      </a>
                    </li>
                  )}
                </ul>
              </section>
            </aside>
          </div>
        </div>
      )}
      {selected && (
        <VersionStatusModal
          open={versionStatusOpen}
          version={selected.versionNumber}
          kind="provider"
          status={selected}
          onCancel={() => setVersionStatusOpen(false)}
          onSave={saveVersionStatus}
        />
      )}
      <DeleteConfirmationModal
        open={deleteOpen}
        title="Delete provider"
        okText="Delete provider"
        confirmValue={providerName}
        message={
          <>
            This permanently deletes <strong>{providerName}</strong> and all {versions.length} of its versions from the{" "}
            {organizationName} registry. Configurations that require <code>{address}</code> fail on their next init.
            {provider?.attributes.imported
              ? " You can add it again from the public registry."
              : " This cannot be undone."}
          </>
        }
        onConfirm={() => {
          setDeleteOpen(false);
          handleDelete();
        }}
        onCancel={() => setDeleteOpen(false)}
      />
    </PageWrapper>
  );
};
