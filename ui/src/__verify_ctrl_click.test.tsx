import { render, screen } from "@testing-library/react";
import { List } from "antd";
import { Link, MemoryRouter } from "react-router-dom";
import OrganizationTable from "./modules/organizations/components/OrganizationTable/OrganizationTable";
import OrganizationGridItem from "./modules/organizations/components/OrganizationGrid/OrganizationGridItem";
import WorkspaceTable from "./modules/workspaces/components/WorkspaceTable/WorkspaceTable";
import WorkspaceCard from "./modules/workspaces/components/WorkspaceCard";

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

test("workspace card list item overlays a real anchor that stacks above the card", () => {
  // The card list in OrganizationDetailsPage puts an absolutely-positioned <Link>
  // behind each <WorkspaceCard>. antd's `.ant-card` is `position: relative`, so the
  // card and its content paint in the same layer as (and, being later in the DOM,
  // on top of) an overlay link with `z-index: 0` — which swallows every click.
  // The overlay must carry a positive z-index to sit above the card.
  render(
    <MemoryRouter>
      <List
        dataSource={[workspace]}
        renderItem={(item) => (
          <List.Item style={{ position: "relative" }}>
            <Link
              to={`/organizations/org-1/workspaces/${item.id}`}
              aria-label={`Open workspace ${item.name}`}
              style={{ position: "absolute", inset: 0, zIndex: 1 }}
            />
            <WorkspaceCard tags={[]} item={item} />
          </List.Item>
        )}
      />
    </MemoryRouter>
  );

  const link = screen.getByRole("link", { name: /open workspace my workspace/i });
  expect(link.tagName).toBe("A");
  expect(link.getAttribute("href")).toBe("/organizations/org-1/workspaces/ws-1");
  expect(link.style.position).toBe("absolute");
  expect(Number(link.style.zIndex)).toBeGreaterThan(0);
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
