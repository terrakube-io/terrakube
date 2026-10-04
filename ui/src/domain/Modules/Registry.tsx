import { Flex, Input, Pagination, Segmented, Spin, Typography } from "antd";
import { CloudUploadOutlined, ExportOutlined, SearchOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { EmptyState } from "@/components/feedback/EmptyState";
import { LinkButton } from "@/components/navigation/LinkButton";
import { ModuleList } from "./ModuleList";
import ModuleTable from "./components/ModuleTable";
import { ProviderList } from "../Providers/ProviderList";
import ProviderTable from "../Providers/components/ProviderTable";
import axiosInstance from "../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { FlatModule } from "../types";
import { FlatProvider } from "../Providers/types";
import { ErrorInformation } from "@/modules/api/types";
import ListViewToggle from "@/components/display/ListViewToggle/ListViewToggle";
import { getStoredListViewMode, ListViewMode } from "@/components/display/ListViewToggle/listViewPreference";
import "./Module.css";

const PAGE_SIZE = 10;

const matches = (item: { name: string; description?: string }, filter: string) =>
  item.name.toLowerCase().includes(filter) || !!item.description?.toLowerCase().includes(filter);

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
  const response = await axiosInstance.get(`organization/${orgId}/provider?include=version`);
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
  const response = await axiosInstance.get(`organization/${orgId}?fields[organization]=name`);
  return response.data.data.attributes.name;
}

export const Registry = ({ setOrganizationName, organizationName }: Props) => {
  const { orgid } = useParams<Params>();
  const [searchParams, setSearchParams] = useSearchParams();
  const [searchFilter, setSearchFilter] = useState("");
  const [page, setPage] = useState(1);
  // undefined until the tab's data has been fetched
  const [modules, setModules] = useState<FlatModule[]>();
  const [providers, setProviders] = useState<FlatProvider[]>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorInformation | undefined>(undefined);
  const [listViewMode, setListViewMode] = useState<ListViewMode>(() => getStoredListViewMode());

  // Track which data has been loaded to avoid re-fetching
  const modulesLoaded = useRef(false);
  const providersLoaded = useRef(false);

  const activeTab = searchParams.get("tab") === "providers" ? "providers" : "modules";

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
    modulesLoaded.current = false;
    providersLoaded.current = false;

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
      } catch {
        setError({ title: "Failed to load registry data" });
      } finally {
        setLoading(false);
      }
    };

    init();
  }, [orgid]);

  const handleTabChange = (key: string) => {
    setSearchParams({ tab: key });
    setPage(1);
    // Lazy load the other tab's data on first switch
    if (key === "providers") {
      loadProviders();
    } else {
      loadModules();
    }
  };

  const filter = searchFilter.trim().toLowerCase();
  const isModules = activeTab === "modules";
  const items = isModules ? modules : providers;
  const filtered = items?.filter((item) => matches(item, filter));
  const pageItems = filtered?.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE) ?? [];
  const noun = isModules ? "modules" : "providers";

  const publishButton = (
    <LinkButton type="primary" icon={<CloudUploadOutlined />} to={`/organizations/${orgid}/registry/create`}>
      Publish module
    </LinkButton>
  );
  const searchButton = (type?: "primary") => (
    <LinkButton
      type={type}
      icon={<SearchOutlined />}
      to={`/organizations/${orgid}/registry/search${isModules ? "" : "?tab=providers"}`}
    >
      Search public registry
    </LinkButton>
  );

  const renderItems = () => {
    if (!filtered) return <Spin className="registry-loading" />;
    if (items!.length === 0) {
      return (
        <EmptyState
          description={
            isModules
              ? `There are no modules in ${organizationName} yet. Publish one from a Git repository, or add one from the public registry.`
              : `There are no providers in ${organizationName} yet. Add one from the public registry.`
          }
        >
          {isModules ? publishButton : searchButton("primary")}
        </EmptyState>
      );
    }
    if (filtered.length === 0) return <EmptyState description={`No ${noun} match "${searchFilter.trim()}".`} />;
    if (listViewMode === "compact") {
      return isModules ? (
        <ModuleTable modules={filtered as FlatModule[]} />
      ) : (
        <ProviderTable providers={filtered as FlatProvider[]} />
      );
    }
    return (
      <>
        {isModules ? (
          <ModuleList modules={pageItems as FlatModule[]} />
        ) : (
          <ProviderList providers={pageItems as FlatProvider[]} />
        )}
        <Pagination
          className="registry-pagination"
          current={page}
          pageSize={PAGE_SIZE}
          total={filtered.length}
          onChange={setPage}
          hideOnSinglePage
        />
      </>
    );
  };

  return (
    <PageWrapper
      title="Registry"
      loadingText="Loading registry..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Registry", path: `/organizations/${orgid}/registry` },
      ]}
      actions={
        <Flex gap="small" align="center" wrap>
          {searchButton()}
          {publishButton}
        </Flex>
      }
    >
      <Typography.Paragraph type="secondary" className="registry-intro">
        Private modules and providers that workspaces in {organizationName} can use.{" "}
        <Typography.Link
          href="https://docs.terrakube.io/user-guide/private-registry"
          target="_blank"
          rel="noopener noreferrer"
        >
          Documentation <ExportOutlined aria-label="opens in a new tab" />
        </Typography.Link>
      </Typography.Paragraph>
      <div className="registry-filter">
        <Segmented
          aria-label="Show"
          value={activeTab}
          onChange={(key) => handleTabChange(key as string)}
          options={[
            { label: "Modules", value: "modules" },
            { label: "Providers", value: "providers" },
          ]}
        />
        <Input
          aria-label={`Search ${noun}`}
          placeholder={`Search ${noun} by name or description`}
          prefix={<SearchOutlined />}
          allowClear
          value={searchFilter}
          onChange={(e) => {
            setSearchFilter(e.target.value);
            setPage(1);
          }}
          className="registry-search"
        />
        <ListViewToggle value={listViewMode} onChange={setListViewMode} />
      </div>
      {filtered && items!.length > 0 && (
        <Typography.Title level={2} className="registry-count">
          {isModules ? "Modules" : "Providers"} ({filtered.length})
        </Typography.Title>
      )}
      {renderItems()}
    </PageWrapper>
  );
};
