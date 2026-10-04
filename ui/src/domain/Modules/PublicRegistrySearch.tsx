import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloudOutlined,
  DownloadOutlined,
  PlusOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { Button, Input, List, Modal, Tabs, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { listProviders } from "../Providers/providerService";
import { formatCount } from "@/modules/utils/formatCount";
import { providerIcon } from "./registryHelpers";
import "./PublicRegistrySearch.css";
import { ProviderModel } from "../Providers/types";
import { ModuleModel } from "../types";

import axiosInstance, { axiosRegistry } from "../../config/axiosConfig";

const { Search } = Input;

type Params = {
  orgid: string;
};

type Props = {
  organizationName: string;
};

// Types for Terraform Registry API responses
type TerraformRegistryProvider = {
  id: string;
  namespace: string;
  name: string;
  alias: string;
  version: string;
  description: string;
  source: string;
  published_at: string;
  downloads: number;
  tier: string;
  logo_url: string;
};

type TerraformRegistryModule = {
  id: string;
  namespace: string;
  name: string;
  provider: string;
  version: string;
  description: string;
  source: string;
  published_at: string;
  downloads: number;
  verified: boolean;
};

type ProviderSearchResponse = {
  providers: {
    id: string;
    namespace: string;
    name: string;
    alias: string | null;
    version: string;
    tag: string;
    description: string;
    source: string;
    published_at: string;
    downloads: number;
    tier: string;
    logo_url: string;
  }[];
  meta: {
    limit: number;
    current_offset: number;
    next_offset: number | null;
    next_url: string | null;
  };
};

type ModuleSearchResponse = {
  modules: TerraformRegistryModule[];
  meta: {
    limit: number;
    current_offset: number;
    next_offset: number | null;
    prev_offset: number | null;
  };
};

// Modal state type
type ModalState = {
  visible: boolean;
  type: "module";
  item: TerraformRegistryModule | null;
};

export const PublicRegistrySearch = ({ organizationName }: Props) => {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  // Tab and query live in the URL so returning from a provider page restores the results.
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get("tab") === "providers" ? "providers" : "modules";
  const searchQuery = searchParams.get("q") ?? "";
  const [providers, setProviders] = useState<TerraformRegistryProvider[]>([]);
  const [modules, setModules] = useState<TerraformRegistryModule[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalState, setModalState] = useState<ModalState>({
    visible: false,
    type: "module",
    item: null,
  });
  const [importing, setImporting] = useState(false);

  // Existing items in the organization's registry
  const [existingProviders, setExistingProviders] = useState<Set<string>>(new Set());
  const [existingModules, setExistingModules] = useState<Set<string>>(new Set());
  const [loadingExisting, setLoadingExisting] = useState(true);

  // Fetch existing providers and modules on mount
  useEffect(() => {
    const fetchExistingItems = async () => {
      if (!orgid) return;

      setLoadingExisting(true);
      try {
        // Fetch existing providers
        const providersResponse = await listProviders(orgid);
        const providerNames = new Set(
          providersResponse.data.map((p: ProviderModel) => p.attributes.name.toLowerCase())
        );
        setExistingProviders(providerNames);

        const modulesResponse = await axiosInstance.get(`organization/${orgid}?include=module`);
        const moduleNames = new Set<string>();
        if (modulesResponse.data.included) {
          modulesResponse.data.included
            .filter((item: any) => item.type === "module")
            .forEach((m: ModuleModel) => {
              // Create a key from name and provider
              const key = `${m.attributes.name}/${m.attributes.provider}`.toLowerCase();
              moduleNames.add(key);
            });
        }
        setExistingModules(moduleNames);
      } catch (error) {
        console.error("Error fetching existing items:", error);
      } finally {
        setLoadingExisting(false);
      }
    };

    fetchExistingItems();
  }, [orgid]);

  // Check if a provider already exists (name is now stored as just the short name)
  const isProviderImported = (provider: TerraformRegistryProvider): boolean => {
    return existingProviders.has(provider.name.toLowerCase());
  };

  // Check if a module already exists
  const isModuleImported = (module: TerraformRegistryModule): boolean => {
    const key = `${module.name}/${module.provider}`.toLowerCase();
    return existingModules.has(key);
  };

  const searchProviders = async (query: string) => {
    if (!query.trim()) {
      setProviders([]);
      return;
    }

    setLoading(true);
    try {
      // Use backend proxy to avoid CORS issues
      const response = await axiosRegistry.get<ProviderSearchResponse>("/registry/v1/providers", {
        params: {
          q: query,
          limit: 20,
        },
      });

      const mappedProviders: TerraformRegistryProvider[] = (response.data.providers || []).map((item) => ({
        id: item.id,
        namespace: item.namespace,
        name: item.name,
        alias: item.alias || "",
        version: item.version,
        description: item.description,
        source: item.source,
        published_at: item.published_at,
        downloads: item.downloads,
        tier: item.tier,
        logo_url: item.logo_url,
      }));

      // Official and partner providers first, then by downloads, as on the Terraform Registry.
      const tierRank = (tier: string) => ["official", "partner"].indexOf(tier) + 1 || 3;
      setProviders(mappedProviders.sort((a, b) => tierRank(a.tier) - tierRank(b.tier) || b.downloads - a.downloads));
    } catch (error) {
      console.error("Error searching providers:", error);
      message.error("Failed to search providers");
      setProviders([]);
    } finally {
      setLoading(false);
    }
  };

  const searchModules = async (query: string) => {
    if (!query.trim()) {
      setModules([]);
      return;
    }

    setLoading(true);
    try {
      // Use backend proxy to avoid CORS issues
      const response = await axiosRegistry.get<ModuleSearchResponse>("/registry/v1/modules", {
        params: {
          q: query,
          limit: 20,
        },
      });

      setModules(response.data.modules || []);
    } catch (error) {
      console.error("Error searching modules:", error);
      message.error("Failed to search modules");
      setModules([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (activeTab === "providers") {
      searchProviders(searchQuery);
    } else {
      searchModules(searchQuery);
    }
  }, [activeTab, searchQuery]);

  const handleSearch = (value: string) => {
    // Unchanged params do not re-run the effect, so search again explicitly (e.g. after a failure).
    if (value === searchQuery) {
      if (activeTab === "providers") searchProviders(value);
      else searchModules(value);
      return;
    }
    setSearchParams({ tab: activeTab, ...(value.trim() ? { q: value } : {}) }, { replace: true });
  };

  const handleTabChange = (key: string) => {
    setSearchParams({ tab: key, ...(searchQuery ? { q: searchQuery } : {}) }, { replace: true });
  };

  const openAddModal = (item: TerraformRegistryModule) => {
    setModalState({ visible: true, type: "module", item });
  };

  const closeModal = () => {
    setModalState({ visible: false, type: "module", item: null });
    setImporting(false);
  };

  const handleAddModule = async () => {
    if (!modalState.item || modalState.type !== "module") return;

    const module = modalState.item as TerraformRegistryModule;
    setImporting(true);

    try {
      // Create module in Terrakube
      // Note: registryPath is a computed attribute on the backend, don't send it
      const body = {
        data: {
          type: "module",
          attributes: {
            name: module.name,
            description: module.description || `Imported from Terraform Registry: ${module.namespace}/${module.name}`,
            provider: module.provider,
            source:
              module.source || `https://github.com/${module.namespace}/terraform-${module.provider}-${module.name}`,
          },
        },
      };

      await axiosInstance.post(`organization/${orgid}/module`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      });

      message.success(`Module ${module.namespace}/${module.name} added successfully`);
      closeModal();
      navigate(`/organizations/${orgid}/registry?tab=modules`);
    } catch (error: any) {
      console.error("Error importing module:", error);
      message.error(error.response?.data?.errors?.[0]?.detail || "Failed to add module");
    } finally {
      setImporting(false);
    }
  };

  const renderProviderLogo = (provider: TerraformRegistryProvider) => {
    if (provider.logo_url) {
      return <img src={provider.logo_url} alt={provider.name} />;
    }
    return <CloudOutlined />;
  };

  // The card opens the provider page, where the version is chosen and the provider is added.
  const renderProviderCard = (provider: TerraformRegistryProvider) => (
    <Link
      to={`/organizations/${orgid}/registry/public/providers/${provider.namespace}/${provider.name}`}
      state={{ search: `?${searchParams.toString()}` }}
      className="public-provider-card"
    >
      <span className="public-provider-card-logo" aria-hidden="true">
        {renderProviderLogo(provider)}
      </span>
      <span className="public-provider-card-body">
        <span className="public-provider-card-name">{provider.name}</span>
        <span className="public-provider-card-by">by {provider.namespace}</span>
        <span className="public-provider-card-meta">
          <Tag className="public-provider-card-tier">{provider.tier || "community"}</Tag>
          <span>
            <DownloadOutlined /> {formatCount(provider.downloads)}
          </span>
          {isProviderImported(provider) && (
            <span className="public-provider-card-imported">
              <CheckCircleOutlined /> In your registry
            </span>
          )}
        </span>
      </span>
    </Link>
  );

  const renderModuleCard = (module: TerraformRegistryModule) => {
    const alreadyImported = isModuleImported(module);

    return (
      <div className="public-provider-card public-module-card">
        <span className="public-provider-card-logo public-module-card-logo" aria-hidden="true">
          {providerIcon(module.provider)}
        </span>
        <span className="public-provider-card-body">
          <span className="public-provider-card-name">
            {module.namespace} / {module.name}
          </span>
          {module.description && <span className="public-module-card-description">{module.description}</span>}
          <span className="public-provider-card-meta">
            <span>
              Provider <code>{module.provider}</code>
            </span>
            <span>
              <DownloadOutlined /> {formatCount(module.downloads)}
            </span>
            {alreadyImported && (
              <span className="public-provider-card-imported">
                <CheckCircleOutlined /> In your registry
              </span>
            )}
          </span>
        </span>
        {!alreadyImported && (
          <Button
            icon={<PlusOutlined />}
            aria-label={`Add ${module.namespace}/${module.name}`}
            onClick={() => openAddModal(module)}
          >
            Add
          </Button>
        )}
      </div>
    );
  };

  const getModalItemName = () => (modalState.item ? `${modalState.item.namespace} / ${modalState.item.name}` : "");

  const tabItems = [
    {
      key: "modules",
      label: "Modules",
      children: (
        <List
          split={false}
          dataSource={modules}
          loading={(loading && activeTab === "modules") || loadingExisting}
          locale={{ emptyText: searchQuery ? "No modules found" : "Search for modules" }}
          pagination={{ defaultPageSize: 5, showTotal: (total, range) => `${range[0]} - ${range[1]} of ${total}` }}
          renderItem={(item) => <List.Item className="public-registry-item">{renderModuleCard(item)}</List.Item>}
        />
      ),
    },
    {
      key: "providers",
      label: "Providers",
      children: (
        <List
          split={false}
          dataSource={providers}
          loading={(loading && activeTab === "providers") || loadingExisting}
          locale={{ emptyText: searchQuery ? "No providers found" : "Search for providers" }}
          pagination={{ defaultPageSize: 5, showTotal: (total, range) => `${range[0]} - ${range[1]} of ${total}` }}
          renderItem={(item) => <List.Item className="public-registry-item">{renderProviderCard(item)}</List.Item>}
        />
      ),
    },
  ];

  return (
    <PageWrapper
      title="Public Registry Search"
      width="reading"
      subTitle="Search and import modules and providers from the Terraform Registry"
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
        { label: "Public Registry Search", path: `/organizations/${orgid}/registry/search` },
      ]}
      actions={
        <Button type="default" icon={<ArrowLeftOutlined />}>
          <Link to={`/organizations/${orgid}/registry`}>Back to your registry</Link>
        </Button>
      }
    >
      <div className="public-registry-search">
        <Search
          aria-label="Search Terraform Registry"
          placeholder="Search Terraform Registry..."
          allowClear
          enterButton={
            <>
              <SearchOutlined /> Search
            </>
          }
          size="large"
          defaultValue={searchQuery}
          onSearch={handleSearch}
          loading={loading}
        />
        <Tabs activeKey={activeTab} onChange={handleTabChange} items={tabItems} />
      </div>

      <Modal
        title="Add module to organization"
        open={modalState.visible}
        onCancel={importing ? undefined : closeModal}
        closable={!importing}
        maskClosable={!importing}
        width={560}
        footer={[
          <Button key="add" type="primary" loading={importing} onClick={handleAddModule}>
            Add
          </Button>,
          <Button key="cancel" onClick={closeModal} disabled={importing}>
            Cancel
          </Button>,
        ]}
      >
        <Typography.Paragraph>
          Import this module from the public Terraform Registry to your private registry in{" "}
          <strong>{organizationName}</strong>.
        </Typography.Paragraph>
        <Typography.Text type="secondary">Module to add:</Typography.Text>
        <Typography.Paragraph strong>{getModalItemName()}</Typography.Paragraph>
        <Typography.Paragraph type="secondary">
          This will create the module in your private registry, allowing you to use it in your Terraform configurations
          with your organization&apos;s registry URL.
        </Typography.Paragraph>
      </Modal>
    </PageWrapper>
  );
};
