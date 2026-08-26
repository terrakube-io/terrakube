import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import OrganizationTable from "./modules/organizations/components/OrganizationTable/OrganizationTable";
import OrganizationGridItem from "./modules/organizations/components/OrganizationGrid/OrganizationGridItem";
import WorkspaceTable from "./modules/workspaces/components/WorkspaceTable/WorkspaceTable";

jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions: { managePermission: false }, loading: false }),
}));

const org = {
  id: "org-1",
  name: "Acme Corp",
  description: "desc",
  executionMode: "remote",
  workspaceCount: 2,
  icon: null,
  workspaceStatusCounts: {},
} as any;

const workspace = {
  id: "ws-1",
  name: "My Workspace",
  description: "",
  lastStatus: null,
  lastRun: null,
  terraformVersion: "1.5.0",
  iacType: "terraform",
  normalizedSource: null,
  branch: "main",
  locked: false,
  projectId: null,
  projectName: null,
} as any;

test("organization table row is a real anchor pointing at the org workspaces URL", () => {
  render(
    <MemoryRouter>
      <OrganizationTable organizations={[org]} />
    </MemoryRouter>
  );
  const link = screen.getByRole("link", { name: /open organization acme corp/i });
  expect(link.tagName).toBe("A");
  expect(link.getAttribute("href")).toBe("/organizations/org-1/workspaces");
});

test("organization grid card is a real anchor pointing at the org workspaces URL", () => {
  render(
    <MemoryRouter>
      <OrganizationGridItem organization={org} />
    </MemoryRouter>
  );
  const link = screen.getByRole("link");
  expect(link.tagName).toBe("A");
  expect(link.getAttribute("href")).toBe("/organizations/org-1/workspaces");
  expect(screen.getByText("Acme Corp")).toBeInTheDocument();
});

test("workspace table row is a real anchor pointing at the workspace URL", () => {
  render(
    <MemoryRouter>
      <WorkspaceTable
        organizationId="org-1"
        workspaces={[workspace]}
        onSelectProject={() => {}}
        sortOption="name_asc"
        onSortChange={() => {}}
      />
    </MemoryRouter>
  );
  const link = screen.getByRole("link", { name: /open workspace my workspace/i });
  expect(link.tagName).toBe("A");
  expect(link.getAttribute("href")).toBe("/organizations/org-1/workspaces/ws-1");
});
