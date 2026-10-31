import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import ProjectDetailPage from "../ProjectDetailPage";

jest.mock("../projectService", () => ({
  __esModule: true,
  default: {
    getProject: jest.fn().mockResolvedValue({ isError: false, data: { id: "project-1", name: "Project 1" } }),
    listProjectAccess: jest.fn().mockResolvedValue({ isError: false, data: [] }),
  },
}));
jest.mock("@/modules/api/apiWrapper", () => ({
  apiGet: jest.fn().mockResolvedValue({ isError: false, data: { groups: [] } }),
}));
jest.mock("@/modules/workspaces/workspaceService", () => ({
  __esModule: true,
  default: { listWorkspaces: jest.fn().mockResolvedValue({ isError: false, data: { workspaces: [] } }) },
}));
jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions: { manageWorkspace: true } }),
}));
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));
jest.mock("../ProjectWorkspaces", () => ({
  __esModule: true,
  default: ({ projectId }: { projectId: string }) => <div>Workspaces for {projectId}</div>,
}));
jest.mock("../ProjectAccessTab", () => ({
  __esModule: true,
  default: ({ projectId }: { projectId: string }) => <div>Team access for {projectId}</div>,
}));

function NavigationControls() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <output aria-label="Current URL">{location.pathname + location.search}</output>
      <button type="button" onClick={() => navigate(-1)}>
        Back
      </button>
      <button type="button" onClick={() => navigate(1)}>
        Forward
      </button>
    </>
  );
}

function renderDetail(search = "") {
  return render(
    <MemoryRouter initialEntries={[`/organizations/org-1/projects/project-1${search}`]}>
      <NavigationControls />
      <Routes>
        <Route
          path="/organizations/:orgid/projects/:id"
          element={<ProjectDetailPage organizationName="Example" setOrganizationName={jest.fn()} />}
        />
      </Routes>
    </MemoryRouter>
  );
}

describe("ProjectDetailPage tab links", () => {
  it.each([
    ["workspaces", "Workspaces for project-1"],
    ["teams", "Team access for project-1"],
  ])("opens the %s tab directly on a fresh navigation", async (tab, content) => {
    renderDetail(`?tab=${tab}`);
    expect(await screen.findByText(content)).toBeInTheDocument();
  });

  it.each(["", "?tab=unknown"])("defaults to General for %s", async (search) => {
    renderDetail(search);
    expect(await screen.findByText("General Settings")).toBeInTheDocument();
  });

  it("keeps tabs synchronized with URLs and browser back/forward navigation", async () => {
    renderDetail("?tab=workspaces&example=preserved");
    await screen.findByText("Workspaces for project-1");
    fireEvent.click(screen.getByRole("menuitem", { name: "Teams" }));
    expect(await screen.findByText("Team access for project-1")).toBeInTheDocument();
    expect(screen.getByLabelText("Current URL")).toHaveTextContent("?tab=teams&example=preserved");

    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    expect(await screen.findByText("Workspaces for project-1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Forward" }));
    expect(await screen.findByText("Team access for project-1")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("menuitem", { name: "General" }));
    expect(await screen.findByText("General Settings")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByLabelText("Current URL")).toHaveTextContent(
        "/organizations/org-1/projects/project-1?example=preserved"
      )
    );
  });
});
