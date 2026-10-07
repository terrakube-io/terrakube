import { CheckCircleOutlined, CloudOutlined, CopyOutlined, ExportOutlined, PlusOutlined } from "@ant-design/icons";
import { Alert, Button, Modal, Select, Tag, Tooltip, Typography, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { LinkButton } from "@/components/navigation/LinkButton";
import { copyValue } from "@/components/settings/IdField/IdField";
import { relativeTime } from "@/modules/utils/dates";
import { formatCount } from "@/modules/utils/formatCount";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { compareVersions } from "../Workspaces/Workspaces";
import { getProviderVersions, getPublicProvider, importProvider, listProviders } from "./providerService";
import { ProviderModel, TerraformRegistryProviderDetails, TerraformRegistryProviderVersions } from "./types";
import "./PublicProviderDetails.css";

type Props = {
  organizationName: string;
};

type Params = {
  orgid: string;
  namespace: string;
  name: string;
};

export const PublicProviderDetails = ({ organizationName }: Props) => {
  const { orgid, namespace, name } = useParams<Params>();
  const navigate = useNavigate();
  const location = useLocation();
  const { permissions } = useOrgPermissions();

  const [latest, setLatest] = useState<TerraformRegistryProviderDetails>();
  const [details, setDetails] = useState<TerraformRegistryProviderDetails>();
  const [versions, setVersions] = useState<TerraformRegistryProviderVersions>();
  const [selectedVersion, setSelectedVersion] = useState<string>();
  const [existingProviderId, setExistingProviderId] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importFailed, setImportFailed] = useState(false);

  useEffect(() => {
    if (!orgid || !namespace || !name) return;
    let stale = false;
    setLoading(true);
    Promise.all([
      getPublicProvider(namespace, name),
      getProviderVersions(namespace, name),
      // The page still works for users who cannot list the organization's providers.
      listProviders(orgid).catch(() => ({ data: [] as ProviderModel[] })),
    ])
      .then(([provider, versionData, existing]) => {
        if (stale) return;
        setLatest(provider);
        setDetails(provider);
        setVersions(versionData);
        // The registry reports its latest stable release; fall back to the highest listed version.
        const sorted = (versionData.versions ?? []).map((v) => v.version).sort((a, b) => compareVersions(b, a));
        setSelectedVersion(provider.version || sorted[0]);
        setExistingProviderId(existing.data.find((p) => p.attributes.name.toLowerCase() === name.toLowerCase())?.id);
      })
      .catch(() => !stale && setError(`Could not load ${namespace}/${name} from the public registry.`))
      .finally(() => !stale && setLoading(false));
    return () => {
      stale = true;
    };
  }, [orgid, namespace, name]);

  // Published date and source belong to the selected version.
  useEffect(() => {
    if (!namespace || !name || !selectedVersion || !latest) return;
    if (selectedVersion === latest.version) {
      setDetails(latest);
      return;
    }
    // Ignore responses for a version the user has already switched away from.
    let stale = false;
    getPublicProvider(namespace, name, selectedVersion)
      .then((d) => !stale && setDetails(d))
      .catch(() => !stale && setDetails(undefined));
    return () => {
      stale = true;
    };
  }, [namespace, name, selectedVersion, latest]);

  const versionOptions = useMemo(
    () =>
      (versions?.versions ?? [])
        .map((v) => v.version)
        .sort((a, b) => compareVersions(b, a))
        .map((v) => ({ value: v, label: v === latest?.version ? `${v} (latest)` : v })),
    [versions, latest]
  );

  const registryHostname = useMemo(() => {
    try {
      return new URL(window._env_.REACT_APP_REGISTRY_URI).hostname;
    } catch {
      return "registry.example.com";
    }
  }, []);

  const snippet = `terraform {
  required_providers {
    ${name} = {
      source  = "${registryHostname}/${organizationName.toLowerCase()}/${name}"
      version = "${selectedVersion ?? ""}"
    }
  }
}`;

  const handleImport = async () => {
    if (!orgid || !namespace || !name || !selectedVersion || !versions) return;
    setImporting(true);
    setImportFailed(false);
    try {
      const description = latest?.description || (latest?.source ? `Source: ${latest.source}` : `${namespace}/${name}`);
      const { provider } = await importProvider(orgid, namespace, name, selectedVersion, description, versions);
      message.success(`Provider ${namespace}/${name} ${selectedVersion} added to ${organizationName}`);
      navigate(`/organizations/${orgid}/registry/providers/${provider.id}`);
    } catch (e) {
      setImportFailed(true);
      message.error(e instanceof Error ? e.message : "Failed to add provider");
      // A partial import may have created the provider; offer it instead of a duplicate Add.
      const existing = await listProviders(orgid).catch(() => ({ data: [] as ProviderModel[] }));
      setExistingProviderId(existing.data.find((p) => p.attributes.name.toLowerCase() === name.toLowerCase())?.id);
      setImporting(false);
    }
  };

  // The search page passes its query string so the breadcrumb returns to the same results.
  const searchQuery = (location.state as { search?: string } | null)?.search ?? "?tab=providers";
  const searchPath = `/organizations/${orgid}/registry/search${searchQuery}`;

  return (
    <PageWrapper
      title={name ?? "Provider"}
      showTitle={false}
      loading={loading}
      loadingText="Loading provider..."
      error={error}
      width="reading"
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
        { label: "Public Registry Search", path: searchPath },
        { label: `${namespace}/${name}` },
      ]}
    >
      {latest && (
        <div className="public-provider">
          <header className="public-provider-header">
            <span className="public-provider-logo" aria-hidden="true">
              {latest.logo_url ? <img src={latest.logo_url} alt="" /> : <CloudOutlined />}
            </span>
            <div className="public-provider-title">
              <Typography.Title level={2}>{name}</Typography.Title>
              <Tag className="public-provider-tier">{latest.tier || "community"}</Tag>
              <Typography.Text type="secondary" className="public-provider-by">
                By {namespace}
              </Typography.Text>
            </div>
            {existingProviderId ? (
              <LinkButton
                to={`/organizations/${orgid}/registry/providers/${existingProviderId}`}
                icon={<CheckCircleOutlined />}
              >
                In your registry
              </LinkButton>
            ) : (
              <Tooltip
                title={
                  permissions.manageProvider ? undefined : "You need the Manage providers permission to add a provider."
                }
              >
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  disabled={!selectedVersion || !permissions.manageProvider}
                  onClick={() => setConfirmOpen(true)}
                >
                  Add to Terrakube
                </Button>
              </Tooltip>
            )}
          </header>

          {latest.description && <Typography.Paragraph type="secondary">{latest.description}</Typography.Paragraph>}

          <section className="public-provider-section">
            <Typography.Title level={4}>Provider details</Typography.Title>
            <Typography.Paragraph type="secondary">
              Choose a version to see when it was published and copy its configuration.
            </Typography.Paragraph>
            <label className="public-provider-label" htmlFor="public-provider-version">
              Select version
            </label>
            <Select
              id="public-provider-version"
              className="public-provider-version"
              value={selectedVersion}
              onChange={setSelectedVersion}
              options={versionOptions}
              showSearch
            />

            <dl className="public-provider-facts">
              <div>
                <dt>Downloads</dt>
                <dd>{formatCount(latest.downloads)}</dd>
              </div>
              <div>
                <dt>Source</dt>
                <dd>
                  {details?.source ? (
                    <Typography.Link href={details.source} target="_blank" rel="noopener noreferrer">
                      {details.source.replace(/^https?:\/\/(www\.)?github\.com\//, "")}
                      {"\u00a0"}
                      <ExportOutlined />
                    </Typography.Link>
                  ) : (
                    "—"
                  )}
                </dd>
              </div>
              <div>
                <dt>Published</dt>
                <dd title={details?.published_at}>{relativeTime(details?.published_at) ?? "—"}</dd>
              </div>
            </dl>
          </section>

          <section className="public-provider-section">
            <Typography.Title level={4}>Usage instructions</Typography.Title>
            <Typography.Paragraph type="secondary">
              After you add the provider, use this snippet in your Terraform configuration and run{" "}
              <Typography.Text code>terraform init</Typography.Text>.
            </Typography.Paragraph>
            <pre className="public-provider-snippet">{snippet}</pre>
            <Button
              icon={<CopyOutlined />}
              onClick={() =>
                copyValue(snippet).then(
                  () => message.success("Configuration copied"),
                  () => message.error("Could not copy the configuration")
                )
              }
            >
              Copy configuration
            </Button>
          </section>
        </div>
      )}

      <Modal
        title="Add public provider to organization"
        open={confirmOpen}
        onCancel={importing ? undefined : () => setConfirmOpen(false)}
        closable={!importing}
        maskClosable={!importing}
        footer={[
          <Button key="add" type="primary" loading={importing} onClick={handleImport}>
            Add
          </Button>,
          <Button key="cancel" disabled={importing} onClick={() => setConfirmOpen(false)}>
            Cancel
          </Button>,
        ]}
      >
        <Typography.Paragraph>
          You are adding a provider from the public registry to your private registry in the{" "}
          <strong>{organizationName}</strong> organization.
        </Typography.Paragraph>
        <Typography.Text type="secondary">Provider to add:</Typography.Text>
        <Typography.Paragraph strong>
          {namespace} / {name} · {selectedVersion}
        </Typography.Paragraph>
        {importFailed && <Alert type="error" showIcon title="The provider could not be added." />}
      </Modal>
    </PageWrapper>
  );
};
