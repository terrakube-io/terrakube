import { Button, Flex, Space } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { ErrorInformation } from "@/modules/api/types";
import OrganizationGrid from "./components/OrganizationGrid/OrganizationGrid";
import OrganizationTable from "./components/OrganizationTable/OrganizationTable";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import ListViewToggle from "@/components/display/ListViewToggle/ListViewToggle";
import { getStoredListViewMode, ListViewMode } from "@/components/display/ListViewToggle/listViewPreference";
import { EmptyState } from "@/components/feedback/EmptyState";
import { useOrganizationSummaries } from "./OrganizationSummaryContext";

export default function OrganizationsPickerPage() {
  const { organizations, loading, error } = useOrganizationSummaries();
  const navigate = useNavigate();
  const location = useLocation();
  const orgId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);

  const [listViewMode, setListViewMode] = useState<ListViewMode>(() => getStoredListViewMode());

  useEffect(() => {
    if (loading) {
      return;
    }

    // Skip redirect if explicitly navigating to /organizations
    if (location.pathname === "/organizations") {
      return;
    }

    if (orgId === "" || orgId === null) {
      if (organizations.length === 1) {
        const organization = organizations[0];
        sessionStorage.setItem(ORGANIZATION_ARCHIVE, organization.id);
        sessionStorage.setItem(ORGANIZATION_NAME, organization.name);
        navigate(`/organizations/${organization.id}/workspaces`, { replace: true });
      }
    } else {
      navigate(`/organizations/${orgId}/workspaces`, { replace: true });
    }
  }, [loading, location.pathname, navigate, orgId, organizations]);

  const errorInformation: ErrorInformation | undefined = error
    ? { title: "Failed to load organizations", message: error.message }
    : undefined;

  return (
    <PageWrapper
      title="Organizations"
      subTitle="Manage your organizations"
      error={errorInformation}
      loading={loading}
      loadingText="Loading organizations..."
      breadcrumbs={[{ label: "Organizations", path: "/" }]}
      actions={
        !loading &&
        organizations.length > 0 && (
          <Space>
            <ListViewToggle value={listViewMode} onChange={setListViewMode} />
            <Button type="primary" icon={<PlusOutlined />}>
              <Link to="/organizations/create">Create organization</Link>
            </Button>
          </Space>
        )
      }
    >
      {!loading && organizations.length === 0 && (
        <Flex justify="center">
          <EmptyState description="You have not created any organizations yet. Create one now to get started with Terrakube.">
            <Button type="primary">
              <Link to="/organizations/create">Create a new organization</Link>
            </Button>
          </EmptyState>
        </Flex>
      )}
      {!loading && organizations.length > 0 && listViewMode === "compact" && (
        <OrganizationTable organizations={organizations} />
      )}
      {!loading && organizations.length > 0 && listViewMode === "cards" && (
        <OrganizationGrid organizations={organizations} />
      )}
    </PageWrapper>
  );
}
