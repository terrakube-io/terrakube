import { Tabs, Button, Dropdown, Input, Space } from "antd";
import { SearchOutlined, CloudUploadOutlined, DownOutlined, AppstoreOutlined, CloudServerOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ModuleList } from "./ModuleList";
import { ProviderList } from "../Providers/ProviderList";
import axiosInstance from "../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { FlatModule, FlatProvider } from "../types";
import { ErrorInformation } from "@/modules/api/types";
import type { MenuProps } from "antd";

type Params = {
  orgid: string;
};

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

// Lightweight fetch: only the fields the list views actually need
// Modules: ~1.5KB instead of ~73KB (98% smaller)
// Providers: ~200B instead of ~2KB
// Org name: ~100B instead of ~75KB

async function fetchModules(orgId: string): Promise<FlatModule[]> {
  const response = await axiosInstance.get(
    `organization/${orgId}/module?fields[module]=name,description,provider,latestVersion,downloadQuantity,createdDate,updatedDate`
  );
  return (response.data.data || []).map((m: any) => ({ id: m.id, ...m.attributes }));
}

async function fetchProviders(orgId: string): Promise<FlatProvider[]> {
  const response = await axiosInstance.get(
    `organization/${orgId}/provider?include=version`
  );
  const data = response.data.data || [];
  const included = response.data.included || [];

  // Build a map of providerId -> latest version number
  const providerVersions: Record<string, string[]> = {};
  for (const item of included) {
    if (item.type === "version") {
      const providerId = item.relationships?.provider?.data?.id;
      if (providerId) {
        if (!providerVersions[providerId]) providerVersions[providerId] = [];
        providerVersions[providerId].push(item.attributes.versionNumber);
      }
    }
  }

  return data.map((p: any) => {
    const versions = providerVersions[p.id] || [];
    // Sort semver descending to get latest
    versions.sort((a: string, b: string) => {
      const pa = a.split(".").map(Number);
      const pb = b.split(".").map(Number);
      for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const diff = (pb[i] || 0) - (pa[i] || 0);
        if (diff !== 0) return diff;
      }
      return 0;
    });
    return {
      id: p.id,
      ...p.attributes,
      latestVersion: versions[0] || undefined,
    };
  });
}

async function fetchOrgName(orgId: string): Promise<string> {
  const response = await axiosInstance.get(
    `organization/${orgId}?fields[organization]=name`
  );
  return response.data.data.attributes.name;
}

export const Registry = ({ setOrganizationName, organizationName }: Props) => {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [searchFilter, setSearchFilter] = useState("");
  const [modules, setModules] = useState<FlatModule[]>([]);
  const [providers, setProviders] = useState<FlatProvider[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorInformation | undefined>(undefined);

  // Track which data has been loaded to avoid re-fetching
  const modulesLoaded = useRef(false);
  const providersLoaded = useRef(false);

  const activeTab = searchParams.get("tab") || "modules";

  const loadModules = useCallback(async () => {
    if (!orgid || modulesLoaded.current) return;
    try {
      const data = await fetchModules(orgid);
      setModules(data);
      modulesLoaded.current = true;
    } catch (err) {
      console.error("Failed to load modules:", err);
      setError({ title: "Failed to load modules" });
    }
  }, [orgid]);

  const loadProviders = useCallback(async () => {
    if (!orgid || providersLoaded.current) return;
    try {
      const data = await fetchProviders(orgid);
      setProviders(data);
      providersLoaded.current = true;
    } catch (err) {
      console.error("Failed to load providers:", err);
      setError({ title: "Failed to load providers" });
    }
  }, [orgid]);

  // On mount: fetch org name + data for the active tab in parallel
  useEffect(() => {
    if (!orgid) return;
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid);

    const init = async () => {
      setLoading(true);
      setError(undefined);
      try {
        // Fetch org name in parallel with the active tab's data
        const promises: Promise<any>[] = [
          fetchOrgName(orgid).then((name) => {
            sessionStorage.setItem(ORGANIZATION_NAME, name);
            setOrganizationName(name);
          }),
        ];

        if (activeTab === "providers") {
          promises.push(
            fetchProviders(orgid).then((data) => {
              setProviders(data);
              providersLoaded.current = true;
            })
          );
        } else {
          promises.push(
            fetchModules(orgid).then((data) => {
              setModules(data);
              modulesLoaded.current = true;
            })
          );
        }

        await Promise.all(promises);
      } catch (err) {
        setError({ title: "Failed to load registry data" });
      } finally {
        setLoading(false);
      }
    };

    init();
  }, [orgid]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleTabChange = (key: string) => {
    setSearchParams({ tab: key });
    // Lazy load the other tab's data on first switch
    if (key === "providers") {
      loadProviders();
    } else {
      loadModules();
    }
  };

  const handleSearchPublicRegistry = () => {
    navigate(`/organizations/${orgid}/registry/search`);
  };

  const publishMenuItems: MenuProps["items"] = [
    {
      key: "module",
      label: "Publish module",
      onClick: () => navigate(`/organizations/${orgid}/registry/create`),
    },
  ];

  const tabItems = [
    {
      key: "modules",
      label: (
        <span>
          <AppstoreOutlined style={{ marginRight: 8 }} />
          Modules
        </span>
      ),
      children: (
        <ModuleList
          modules={modules}
          searchFilter={searchFilter}
        />
      ),
    },
    {
      key: "providers",
      label: (
        <span>
          <CloudServerOutlined style={{ marginRight: 8 }} />
          Providers
        </span>
      ),
      children: <ProviderList providers={providers} searchFilter={searchFilter} />,
    },
  ];

  return (
    <PageWrapper
      title="Registry"
      subTitle={`Modules and providers in the ${organizationName} organization`}
      loadingText="Loading registry..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
      ]}
      fluid
      innerClassName="registry-centered"
      contentClassName="registry-centered"
      actions={
        <Space>
          <Button
            type="default"
            icon={<SearchOutlined />}
            onClick={handleSearchPublicRegistry}
          >
            Search public registry
          </Button>
          <Dropdown menu={{ items: publishMenuItems }} trigger={["click"]}>
            <Button type="primary" icon={<CloudUploadOutlined />}>
              Publish <DownOutlined />
            </Button>
          </Dropdown>
        </Space>
      }
    >
      <div>
        <Input
          placeholder="Filter providers and modules..."
          prefix={<SearchOutlined />}
          allowClear
          size="large"
          value={searchFilter}
          onChange={(e) => setSearchFilter(e.target.value)}
          style={{ width: "100%", maxWidth: 500, marginBottom: 24 }}
        />
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          items={tabItems}
          size="large"
        />
      </div>
    </PageWrapper>
  );
};
