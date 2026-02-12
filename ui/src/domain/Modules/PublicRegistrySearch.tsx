import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloudOutlined,
  DownloadOutlined,
  LoadingOutlined,
  PlusOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import {
  Button,
  Card,
  Input,
  List,
  Modal,
  Select,
  Space,
  Spin,
  Steps,
  Tabs,
  Typography,
  message,
} from "antd";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { FaAws, FaGoogle } from "react-icons/fa";
import { VscAzure } from "react-icons/vsc";
import { useNavigate, useParams } from "react-router-dom";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { importProvider, getProviderVersions, listProviders } from "../Providers/providerService";
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
  type: "provider" | "module";
  item: TerraformRegistryProvider | TerraformRegistryModule | null;
};

// Import progress state
type ImportProgress = {
  step: number;
  status: "waiting" | "process" | "finish" | "error";
  message: string;
};

type VersionInfo = {
  version: string;
  protocols: string[];
  platforms: { os: string; arch: string }[];
};

export const PublicRegistrySearch = ({ organizationName }: Props) => {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<"modules" | "providers">("modules");
  const [searchQuery, setSearchQuery] = useState("");
  const [providers, setProviders] = useState<TerraformRegistryProvider[]>([]);
  const [modules, setModules] = useState<TerraformRegistryModule[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalState, setModalState] = useState<ModalState>({
    visible: false,
    type: "provider",
    item: null,
  });
  const [importing, setImporting] = useState(false);
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [availableVersions, setAvailableVersions] = useState<VersionInfo[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<string>("");
  const [importProgress, setImportProgress] = useState<ImportProgress[]>([
    { step: 0, status: "waiting", message: "Create provider" },
    { step: 1, status: "waiting", message: "Create version" },
    { step: 2, status: "waiting", message: "Add platform implementations" },
  ]);
  
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
          providersResponse.data.map((p: ProviderModel) => 
            p.attributes.name.toLowerCase()
          )
        );
        setExistingProviders(providerNames);

        // Fetch existing modules
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

  // Check if a provider already exists
  const isProviderImported = (provider: TerraformRegistryProvider): boolean => {
    // Check both "namespace/name" format and just "name"
    const fullName = `${provider.namespace}/${provider.name}`.toLowerCase();
    return existingProviders.has(fullName) || existingProviders.has(provider.name.toLowerCase());
  };

  // Check if a module already exists
  const isModuleImported = (module: TerraformRegistryModule): boolean => {
    const key = `${module.name}/${module.provider}`.toLowerCase();
    return existingModules.has(key);
  };

  const handleBack = () => {
    navigate(`/organizations/${orgid}/registry`);
  };

  const searchProviders = async (query: string) => {
    if (!query.trim()) {
      setProviders([]);
      return;
    }

    setLoading(true);
    try {
      // Use backend proxy to avoid CORS issues
      const response = await axiosRegistry.get<ProviderSearchResponse>(
        "/registry/v1/providers",
        {
          params: {
            q: query,
            limit: 20,
          },
        }
      );

      const mappedProviders: TerraformRegistryProvider[] = (response.data.providers || []).map(
        (item) => ({
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
        })
      );

      setProviders(mappedProviders);
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
      const response = await axiosRegistry.get<ModuleSearchResponse>(
        "/registry/v1/modules",
        {
          params: {
            q: query,
            limit: 20,
          },
        }
      );

      setModules(response.data.modules || []);
    } catch (error) {
      console.error("Error searching modules:", error);
      message.error("Failed to search modules");
      setModules([]);
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = (value: string) => {
    setSearchQuery(value);
    if (activeTab === "providers") {
      searchProviders(value);
    } else {
      searchModules(value);
    }
  };

  const handleTabChange = (key: string) => {
    setActiveTab(key as "modules" | "providers");
    if (searchQuery) {
      if (key === "providers") {
        searchProviders(searchQuery);
      } else {
        searchModules(searchQuery);
      }
    }
  };

  const openAddModal = async (
    type: "provider" | "module",
    item: TerraformRegistryProvider | TerraformRegistryModule
  ) => {
    setModalState({ visible: true, type, item });
    setImportProgress([
      { step: 0, status: "waiting", message: "Create provider" },
      { step: 1, status: "waiting", message: "Create version" },
      { step: 2, status: "waiting", message: "Add platform implementations" },
    ]);
    
    if (type === "provider") {
      const provider = item as TerraformRegistryProvider;
      setLoadingVersions(true);
      setAvailableVersions([]);
      setSelectedVersion("");
      
      try {
        const versionsData = await getProviderVersions(provider.namespace, provider.name);
        setAvailableVersions(versionsData.versions || []);
        if (versionsData.versions?.length > 0) {
          setSelectedVersion(versionsData.versions[0].version);
        }
      } catch (error) {
        console.error("Error fetching versions:", error);
        message.error("Failed to fetch available versions");
      } finally {
        setLoadingVersions(false);
      }
    }
  };

  const closeModal = () => {
    setModalState({ visible: false, type: "provider", item: null });
    setAvailableVersions([]);
    setSelectedVersion("");
    setImporting(false);
    setImportProgress([
      { step: 0, status: "waiting", message: "Create provider" },
      { step: 1, status: "waiting", message: "Create version" },
      { step: 2, status: "waiting", message: "Add platform implementations" },
    ]);
  };

  const updateProgress = (stepIndex: number, status: "waiting" | "process" | "finish" | "error", msg?: string) => {
    setImportProgress(prev => prev.map((p, i) => 
      i === stepIndex ? { ...p, status, message: msg || p.message } : p
    ));
  };

  const handleAddProvider = async () => {
    if (!modalState.item || modalState.type !== "provider") return;
    if (!selectedVersion) {
      message.error("Please select a version");
      return;
    }

    const provider = modalState.item as TerraformRegistryProvider;
    setImporting(true);

    // Reset progress
    setImportProgress([
      { step: 0, status: "process", message: "Creating provider..." },
      { step: 1, status: "waiting", message: "Create version" },
      { step: 2, status: "waiting", message: "Add platform implementations" },
    ]);

    try {
      // Step 1: Create provider
      updateProgress(0, "process", "Creating provider...");
      
      // Get version info for platforms
      const versionInfo = availableVersions.find(v => v.version === selectedVersion);
      const platformCount = versionInfo?.platforms?.length || 0;
      
      // Build a meaningful description from available data
      const desc = provider.description
        || (provider.source ? `Source: ${provider.source}` : "")
        || `${provider.namespace}/${provider.name}`;
      // Pass pre-fetched versions to avoid duplicate API call
      const prefetchedVersions = { versions: availableVersions };
      await importProvider(orgid!, provider.namespace, provider.name, selectedVersion, desc, prefetchedVersions);
      
      updateProgress(0, "finish", "Provider created");
      updateProgress(1, "finish", "Version created");
      updateProgress(2, "finish", `${platformCount} platform(s) added`);

      message.success(
        `Provider ${provider.namespace}/${provider.name} v${selectedVersion} added successfully`
      );
      
      // Update existing providers set
      setExistingProviders(prev => new Set([...prev, `${provider.namespace}/${provider.name}`.toLowerCase()]));
      
      // Small delay to show completion
      setTimeout(() => {
        closeModal();
        navigate(`/organizations/${orgid}/registry?tab=providers`);
      }, 1000);
    } catch (error: any) {
      console.error("Error importing provider:", error);
      const currentStep = importProgress.findIndex(p => p.status === "process");
      if (currentStep >= 0) {
        updateProgress(currentStep, "error", `Failed: ${error.message || "Unknown error"}`);
      }
      message.error(error.message || "Failed to add provider");
      setImporting(false);
    }
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
            source: module.source || `https://github.com/${module.namespace}/terraform-${module.provider}-${module.name}`,
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

  const formatDownloads = (downloads: number): string => {
    if (downloads >= 1_000_000_000) {
      return `${(downloads / 1_000_000_000).toFixed(1)}B`;
    }
    if (downloads >= 1_000_000) {
      return `${(downloads / 1_000_000).toFixed(1)}M`;
    }
    if (downloads >= 1_000) {
      return `${(downloads / 1_000).toFixed(1)}K`;
    }
    return downloads.toString();
  };

  const renderProviderLogo = (provider: TerraformRegistryProvider) => {
    if (provider.logo_url) {
      return (
        <img
          src={provider.logo_url}
          alt={provider.name}
          style={{ width: 32, height: 32, objectFit: "contain" }}
        />
      );
    }
    return <CloudOutlined style={{ fontSize: 32 }} />;
  };

  const renderModuleProviderIcon = (providerName: string) => {
    switch (providerName?.toLowerCase()) {
      case "azurerm":
      case "azure":
        return (
          <IconContext.Provider value={{ color: "#008AD7", size: "1.2em" }}>
            <VscAzure />
          </IconContext.Provider>
        );
      case "aws":
        return (
          <IconContext.Provider value={{ color: "#232F3E", size: "1.2em" }}>
            <FaAws />
          </IconContext.Provider>
        );
      case "google":
      case "gcp":
        return (
          <IconContext.Provider value={{ color: "#4285F4", size: "1.2em" }}>
            <FaGoogle />
          </IconContext.Provider>
        );
      default:
        return <CloudOutlined />;
    }
  };

  const renderProviderCard = (provider: TerraformRegistryProvider) => {
    const alreadyImported = isProviderImported(provider);
    
    return (
      <Card
        hoverable
        className="module-card"
        style={{ width: "100%" }}
        styles={{ body: { padding: 0 } }}
      >
        <div className="module-card-body">
          <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
            <div style={{ flexShrink: 0, width: 36, height: 36, display: "flex", alignItems: "center", justifyContent: "center" }}>
              {renderProviderLogo(provider)}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <Typography.Text strong style={{ fontSize: 16, color: "#222b3d" }}>
                  {provider.namespace} / {provider.name}
                </Typography.Text>
                {alreadyImported && (
                  <Typography.Text type="secondary" style={{ display: "flex", alignItems: "center", gap: 4, fontSize: 12 }}>
                    <CheckCircleOutlined style={{ color: "#52c41a" }} />
                    In your Registry
                  </Typography.Text>
                )}
              </div>
              <div className="module-card-desc">
                {provider.description || "No description available"}
              </div>
            </div>
            {!alreadyImported && (
              <Button
                icon={<PlusOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  openAddModal("provider", provider);
                }}
                style={{ flexShrink: 0 }}
              >
                Add
              </Button>
            )}
          </div>
        </div>
        {/* Footer with separator */}
        <div style={{ borderTop: "1px solid #f0f0f0", padding: "10px 24px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <Space size={16}>
            <Space size={4}>
              <DownloadOutlined style={{ fontSize: 13, color: "#8c97a8" }} />
              <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>
                {formatDownloads(provider.downloads)}
              </Typography.Text>
            </Space>
          </Space>
          <Space size={6}>
            {renderProviderLogo(provider)}
            <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>provider</Typography.Text>
          </Space>
        </div>
      </Card>
    );
  };

  const renderModuleCard = (module: TerraformRegistryModule) => {
    const alreadyImported = isModuleImported(module);
    
    return (
      <Card
        hoverable
        className="module-card"
        style={{ width: "100%" }}
        styles={{ body: { padding: 0 } }}
      >
        <div className="module-card-body">
          <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
            <div style={{ flexShrink: 0, width: 36, height: 36, display: "flex", alignItems: "center", justifyContent: "center" }}>
              {renderModuleProviderIcon(module.provider)}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <Typography.Text strong style={{ fontSize: 16, color: "#222b3d" }}>
                  {module.namespace} / {module.name}
                </Typography.Text>
                {alreadyImported && (
                  <Typography.Text type="secondary" style={{ display: "flex", alignItems: "center", gap: 4, fontSize: 12 }}>
                    <CheckCircleOutlined style={{ color: "#52c41a" }} />
                    In your Registry
                  </Typography.Text>
                )}
              </div>
              <div className="module-card-desc">
                {module.description || "No description available"}
              </div>
            </div>
            {!alreadyImported && (
              <Button
                icon={<PlusOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  openAddModal("module", module);
                }}
                style={{ flexShrink: 0 }}
              >
                Add
              </Button>
            )}
          </div>
        </div>
        {/* Footer with separator */}
        <div style={{ borderTop: "1px solid #f0f0f0", padding: "10px 24px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <Space size={16}>
            <Space size={4}>
              <DownloadOutlined style={{ fontSize: 13, color: "#8c97a8" }} />
              <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>
                {formatDownloads(module.downloads)}
              </Typography.Text>
            </Space>
          </Space>
          <Space size={6}>
            {renderModuleProviderIcon(module.provider)}
            <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>{module.provider}</Typography.Text>
          </Space>
        </div>
      </Card>
    );
  };

  const getModalTitle = () => {
    return modalState.type === "provider"
      ? "Add provider to organization"
      : "Add module to organization";
  };

  const getModalItemName = () => {
    if (!modalState.item) return "";
    if (modalState.type === "provider") {
      const provider = modalState.item as TerraformRegistryProvider;
      return `${provider.namespace} / ${provider.name}`;
    }
    const module = modalState.item as TerraformRegistryModule;
    return `${module.namespace} / ${module.name}`;
  };

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
          renderItem={(item) => <List.Item style={{ padding: "6px 0" }}>{renderModuleCard(item)}</List.Item>}
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
          renderItem={(item) => <List.Item style={{ padding: "6px 0" }}>{renderProviderCard(item)}</List.Item>}
        />
      ),
    },
  ];

  return (
    <PageWrapper
      title="Public Registry Search"
      subTitle="Search and import modules and providers from the Terraform Registry"
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
        { label: "Public Registry Search", path: `/organizations/${orgid}/registry/search` },
      ]}
      actions={
        <Button type="default" icon={<ArrowLeftOutlined />} onClick={handleBack}>
          Back to your registry
        </Button>
      }
    >
      <div className="registry-centered" style={{ marginTop: 24 }}>
        <Search
          placeholder="Search Terraform Registry..."
          allowClear
          enterButton={<><SearchOutlined /> Search</>}
          size="large"
          onSearch={handleSearch}
          loading={loading}
          style={{ marginBottom: 24 }}
        />
        <Tabs activeKey={activeTab} onChange={handleTabChange} items={tabItems} />
      </div>

      <Modal
        title={getModalTitle()}
        open={modalState.visible}
        onCancel={importing ? undefined : closeModal}
        closable={!importing}
        maskClosable={!importing}
        width={560}
        footer={importing ? null : [
          <Button key="cancel" onClick={closeModal}>
            Cancel
          </Button>,
          <Button
            key="add"
            type="primary"
            loading={loadingVersions}
            disabled={modalState.type === "provider" && !selectedVersion}
            onClick={modalState.type === "provider" ? handleAddProvider : handleAddModule}
          >
            Import {modalState.type === "provider" ? "Provider" : "Module"}
          </Button>,
        ]}
      >
        {importing ? (
          <div style={{ padding: "20px 0" }}>
            <Typography.Title level={5} style={{ marginBottom: 24 }}>
              Importing {getModalItemName()}...
            </Typography.Title>
            <Steps
              direction="vertical"
              size="small"
              current={importProgress.findIndex(p => p.status === "process")}
              items={importProgress.map((p) => ({
                title: p.message,
                status: p.status,
                icon: p.status === "process" ? <LoadingOutlined /> : 
                      p.status === "finish" ? <CheckCircleOutlined /> : undefined,
              }))}
            />
          </div>
        ) : (
          <>
            <Typography.Paragraph>
              Import this {modalState.type} from the public Terraform Registry to your private
              registry in <strong>{organizationName}</strong>.
            </Typography.Paragraph>
            
            <div style={{ background: "#f5f5f5", padding: 16, borderRadius: 8, marginBottom: 16 }}>
              <Typography.Text strong style={{ fontSize: 16 }}>
                {getModalItemName()}
              </Typography.Text>
              {modalState.type === "provider" && modalState.item && (
                <Typography.Paragraph type="secondary" style={{ margin: "8px 0 0 0" }}>
                  {(modalState.item as TerraformRegistryProvider).description || "No description"}
                </Typography.Paragraph>
              )}
            </div>

            {modalState.type === "provider" && (
              <div style={{ marginBottom: 16 }}>
                <Typography.Text strong style={{ display: "block", marginBottom: 8 }}>
                  Select Version
                </Typography.Text>
                {loadingVersions ? (
                  <div style={{ textAlign: "center", padding: 20 }}>
                    <Spin tip="Loading versions..." />
                  </div>
                ) : (
                  <Select
                    style={{ width: "100%" }}
                    placeholder="Select a version"
                    value={selectedVersion}
                    onChange={setSelectedVersion}
                    options={[...availableVersions]
                      .sort((a, b) => {
                        // Sort by semver descending (latest first)
                        const pa = a.version.split(".").map(Number);
                        const pb = b.version.split(".").map(Number);
                        for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
                          const diff = (pb[i] || 0) - (pa[i] || 0);
                          if (diff !== 0) return diff;
                        }
                        return 0;
                      })
                      .map(v => ({
                        value: v.version,
                        label: `v${v.version}`,
                      }))}
                  />
                )}
              </div>
            )}

            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
              This will create the {modalState.type} in your private registry, allowing you to use it
              in your Terraform configurations with your organization's registry URL.
            </Typography.Paragraph>
          </>
        )}
      </Modal>
    </PageWrapper>
  );
};
