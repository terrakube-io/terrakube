import { Button, Empty, Flex } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import "antd/dist/reset.css";
import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import organizationService from "@/modules/organizations/organizationService";
import { OrganizationModel } from "./types";
import { ErrorInformation } from "@/modules/api/types";
import OrganizationGrid from "./components/OrganizationGrid/OrganizationGrid";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";

export default function OrganizationsPickerPage() {
  const [organizations, setOrganizations] = useState<OrganizationModel[]>([]);
  const navigate = useNavigate();
  const orgId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ErrorInformation | undefined>(undefined);

  const execute = async () => {
    setLoading(true);
    try {
      const orgs = await organizationService.listOrganizationsGraphQL();
      setOrganizations(
        orgs.map((org) => ({
          id: org.id,
          name: org.name,
          description: org.description,
          executionMode: org.executionMode,
          icon: org.icon,
        }))
      );
    } catch (err: any) {
      setError({
        title: "Failed to load organizations",
        message: err?.message || "An unexpected error occurred",
      });
    } finally {
      setLoading(false);
    }
  };

  async function initPage() {
    // Skip redirect if explicitly navigating to /organizations
    if (window.location.pathname === "/organizations") {
      await execute();
      return;
    }

    if (orgId === "" || orgId === null) {
      await execute();
    } else {
      const orgName = sessionStorage.getItem(ORGANIZATION_NAME);
      if (orgName) {
        window.location.href = `/organizations/${orgId}/workspaces`;
      } else {
        navigate(`/organizations/${orgId}/workspaces`, { replace: true });
      }
    }
  }

  useEffect(() => {
    initPage();
  }, []);

  useEffect(() => {
    if (organizations.length === 1) {
      const organization = organizations[0];
      sessionStorage.setItem(ORGANIZATION_ARCHIVE, organization.id);
      sessionStorage.setItem(ORGANIZATION_NAME, organization.name);
      window.location.href = `/organizations/${organization.id}/workspaces`;
    }
  }, [organizations]);

  return (
    <PageWrapper
      title="Organizations"
      subTitle="Manage your organizations"
      error={error}
      loading={loading}
      loadingText="Loading organizations..."
      breadcrumbs={[{ label: "Organizations", path: "/" }]}
      actions={
        !loading &&
        organizations.length > 0 && (
          <Button type="primary" onClick={() => navigate("/organizations/create")}>
            <PlusOutlined /> Create organization
          </Button>
        )
      }
    >
      {!loading && organizations.length === 0 && (
        <Flex justify="center">
          <Empty
            className="page-wrapper-no-content"
            style={{ textAlign: "center" }}
            description="You have not created any organizations yet. Create one now to get stared with Terrakube"
          >
            <Button type="primary">
              <Link to="/organizations/create">Create a new organization</Link>
            </Button>
          </Empty>
        </Flex>
      )}
      {!loading && organizations.length > 0 && <OrganizationGrid organizations={organizations} />}
    </PageWrapper>
  );
}
