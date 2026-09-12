import { render, waitFor } from "@testing-library/react";
import OrganizationsDetailPage from "../OrganizationDetailsPage";
import workspaceService from "@/modules/workspaces/workspaceService";
import projectService from "@/modules/projects/projectService";

jest.mock("react-router-dom", () => ({
  useParams: () => ({ id: "org-2" }),
  Link: ({ children }: { children?: React.ReactNode }) => <span>{children}</span>,
}));
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: function PageWrapper({ children }: { children?: React.ReactNode }) {
    return <>{children}</>;
  },
}));
jest.mock("@/hooks", () => ({ usePolling: jest.fn(), useOrganizationJobStatusSubscription: jest.fn() }));
jest.mock("@/modules/workspaces/components/WorkspaceFilter", () => () => null);
jest.mock("@/modules/workspaces/components/WorkspaceTable/WorkspaceTable", () => () => null);
jest.mock("@/modules/workspaces/workspaceService", () => ({ listWorkspacePage: jest.fn() }));
jest.mock("@/modules/projects/projectService", () => ({ listProjects: jest.fn() }));

const emptyPage = {
  isError: false,
  data: {
    organizationName: "org",
    workspaces: [],
    pageInfo: { hasNextPage: false, totalRecords: 0 },
    statusCounts: {},
    policyCounts: {},
  },
};

describe("OrganizationDetailsPage project filter", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sessionStorage.clear();
    (workspaceService.listWorkspacePage as jest.Mock).mockResolvedValue(emptyPage);
  });

  it("drops a project filter left over from another organization", async () => {
    sessionStorage.setItem("projectFilter", "project-from-org-1");
    (projectService.listProjects as jest.Mock).mockResolvedValue({
      isError: false,
      data: [{ id: "project-2", name: "P2" }],
    });

    render(<OrganizationsDetailPage organizationName="org" setOrganizationName={jest.fn()} />);

    await waitFor(() =>
      expect(workspaceService.listWorkspacePage).toHaveBeenLastCalledWith(
        expect.objectContaining({ organizationId: "org-2", projectId: null }),
        expect.anything()
      )
    );
    expect(sessionStorage.getItem("projectFilter")).toBe("");
  });

  it("keeps a project filter that belongs to the organization", async () => {
    sessionStorage.setItem("projectFilter", "project-2");
    (projectService.listProjects as jest.Mock).mockResolvedValue({
      isError: false,
      data: [{ id: "project-2", name: "P2" }],
    });

    render(<OrganizationsDetailPage organizationName="org" setOrganizationName={jest.fn()} />);

    await waitFor(() => expect(projectService.listProjects).toHaveBeenCalled());
    await waitFor(() => expect(workspaceService.listWorkspacePage).toHaveBeenCalled());
    expect(
      (workspaceService.listWorkspacePage as jest.Mock).mock.calls.every(
        ([request]) => request.projectId === "project-2"
      )
    ).toBe(true);
  });
});
