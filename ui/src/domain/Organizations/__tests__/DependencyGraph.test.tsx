import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { DependencyGraph } from "../DependencyGraph";

const getOrganizationDependencyGraphMock = jest.fn();

jest.mock("@/modules/dependencyGraph/dependencyGraphService", () => ({
  getOrganizationDependencyGraph: (...args: unknown[]) => getOrganizationDependencyGraphMock(...args),
}));

// PageWrapper deep-imports an ESM-only antd submodule Jest can't transform - every test that
// renders a PageWrapper-based page mocks it out the same way (see OrganizationDetailsPage.test.tsx).
// title/actions/children are rendered plainly so assertions on them still work unchanged.
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: function PageWrapper({
    title,
    actions,
    children,
  }: {
    title?: React.ReactNode;
    actions?: React.ReactNode;
    children?: React.ReactNode;
  }) {
    return (
      <div>
        <h2>{title}</h2>
        <div>{actions}</div>
        {children}
      </div>
    );
  },
}));

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/organizations/:id/dependency-graph" element={<DependencyGraph />} />
        <Route path="/organizations/:id/dependency-graph/:workspaceId" element={<DependencyGraph />} />
      </Routes>
    </MemoryRouter>
  );

const renderPage = (organizationId = "org-1") => renderAt(`/organizations/${organizationId}/dependency-graph`);

const renderScopedPage = (organizationId = "org-1", workspaceId = "ws-network") =>
  renderAt(`/organizations/${organizationId}/dependency-graph/${workspaceId}`);

describe("DependencyGraph", () => {
  beforeEach(() => {
    getOrganizationDependencyGraphMock.mockReset();
  });

  it("asks for this organization's whole topology, not one workspace's", async () => {
    getOrganizationDependencyGraphMock.mockResolvedValue({ workspaces: [], edges: [] });
    renderPage("org-42");

    await waitFor(() => expect(getOrganizationDependencyGraphMock).toHaveBeenCalledWith("org-42"));
  });

  it("hides workspaces with no dependencies in the organization view until asked to show them", async () => {
    getOrganizationDependencyGraphMock.mockResolvedValue({
      workspaces: [
        { id: "ws-network", name: "network" },
        { id: "ws-vpn", name: "vpn" },
        { id: "ws-isolated", name: "isolated" },
      ],
      edges: [
        {
          id: "edge-1",
          sourceWorkspaceId: "ws-network",
          destinationWorkspaceId: "ws-vpn",
          synchronizationMode: "EACH",
        },
      ],
    });
    renderPage();

    expect(await screen.findByText("network")).toBeInTheDocument();
    expect(screen.getByText("vpn")).toBeInTheDocument();
    expect(screen.queryByText("isolated")).toBeNull();

    await userEvent.click(screen.getByRole("switch", { name: "Show workspaces without dependencies" }));

    expect(await screen.findByText("isolated")).toBeInTheDocument();
  });

  it("still shows a workspace with no dependencies when it is the one being focused", async () => {
    getOrganizationDependencyGraphMock.mockResolvedValue({
      workspaces: [
        { id: "ws-isolated", name: "isolated" },
        { id: "ws-other", name: "other" },
      ],
      edges: [],
    });
    renderScopedPage("org-1", "ws-isolated");

    expect(await screen.findByText("isolated")).toBeInTheDocument();
    expect(screen.queryByRole("switch", { name: "Show workspaces without dependencies" })).toBeNull();
  });

  it("links a workspace node to its run triggers tab", async () => {
    getOrganizationDependencyGraphMock.mockResolvedValue({
      workspaces: [
        { id: "ws-network", name: "network" },
        { id: "ws-vpn", name: "vpn" },
      ],
      edges: [
        {
          id: "edge-1",
          sourceWorkspaceId: "ws-network",
          destinationWorkspaceId: "ws-vpn",
          synchronizationMode: "EACH",
        },
      ],
    });
    renderPage("org-1");

    // reactflow never actually measures its nodes in jsdom (no ResizeObserver callback), so
    // every node stays visibility:hidden forever and getByRole excludes it from the
    // accessibility tree - see CascadeGraph.test.tsx for the same quirk.
    const text = await screen.findByText("network");
    expect(text.closest("a")).toHaveAttribute("href", "/organizations/org-1/workspaces/ws-network/run-triggers");
  });

  it("shows a search box on the full org view but not the hop/expand controls", async () => {
    getOrganizationDependencyGraphMock.mockResolvedValue({ workspaces: [], edges: [] });
    renderPage();

    await waitFor(() => expect(getOrganizationDependencyGraphMock).toHaveBeenCalled());
    expect(screen.getByRole("combobox", { name: "Find a workspace" })).toBeInTheDocument();
    expect(screen.queryByText("Expand to full organization")).toBeNull();
  });

  describe("scoped to one workspace", () => {
    // The whole org graph is still fetched once - scoping is a client-side filter over it, not
    // a different, narrower API call.
    const CHAIN = {
      workspaces: [
        { id: "ws-network", name: "network" },
        { id: "ws-vpn", name: "vpn" },
        { id: "ws-unrelated", name: "unrelated" },
      ],
      edges: [
        {
          id: "edge-1",
          sourceWorkspaceId: "ws-network",
          destinationWorkspaceId: "ws-vpn",
          synchronizationMode: "EACH",
        },
      ],
    };

    it("still fetches the whole organization, then filters client-side", async () => {
      getOrganizationDependencyGraphMock.mockResolvedValue(CHAIN);
      renderScopedPage("org-1", "ws-network");

      await waitFor(() => expect(getOrganizationDependencyGraphMock).toHaveBeenCalledWith("org-1"));
      expect(await screen.findByText("vpn")).toBeInTheDocument();
      expect(screen.queryByText("unrelated")).toBeNull();
    });

    it("names the focus workspace in the title", async () => {
      getOrganizationDependencyGraphMock.mockResolvedValue(CHAIN);
      renderScopedPage("org-1", "ws-network");

      expect(await screen.findByRole("heading", { name: /network/ })).toBeInTheDocument();
    });

    it("keeps search available alongside expand-to-full-organization", async () => {
      getOrganizationDependencyGraphMock.mockResolvedValue(CHAIN);
      renderScopedPage("org-1", "ws-network");

      const expandLink = (await screen.findByText("Expand to full organization")).closest("a");
      expect(expandLink).toHaveAttribute("href", "/organizations/org-1/dependency-graph");
      expect(screen.getByRole("combobox", { name: "Find a workspace" })).toBeInTheDocument();
    });
  });
});
