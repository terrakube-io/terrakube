import { act, render, waitFor } from "@testing-library/react";
import OrganizationsDetailPage from "../OrganizationDetailsPage";
import workspaceService from "@/modules/workspaces/workspaceService";
import projectService from "@/modules/projects/projectService";
import { message } from "antd";

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
let tableProps: any;
jest.mock("@/modules/workspaces/components/WorkspaceTable/WorkspaceTable", () => (props: any) => {
  tableProps = props;
  return null;
});
jest.mock("antd", () => ({ ...jest.requireActual("antd"), message: { error: jest.fn() } }));
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

  it("reuses facet counts on page turns and surfaces later failures", async () => {
    (projectService.listProjects as jest.Mock).mockResolvedValue({ isError: false, data: [] });
    (workspaceService.listWorkspacePage as jest.Mock).mockResolvedValue({
      ...emptyPage,
      data: { ...emptyPage.data, pageInfo: { hasNextPage: true, totalRecords: 40 } },
    });
    render(<OrganizationsDetailPage organizationName="org" setOrganizationName={jest.fn()} />);
    await waitFor(() => expect(workspaceService.listWorkspacePage).toHaveBeenCalledWith(expect.anything(), true));
    expect(tableProps).toBeDefined();

    (workspaceService.listWorkspacePage as jest.Mock).mockClear();
    await act(async () => tableProps.onPageChange(2, tableProps.pageSize));
    await waitFor(() =>
      expect(workspaceService.listWorkspacePage).toHaveBeenLastCalledWith(
        expect.objectContaining({ after: tableProps.pageSize }),
        false
      )
    );
    expect(message.error).not.toHaveBeenCalled();

    (workspaceService.listWorkspacePage as jest.Mock).mockResolvedValue({
      isError: true,
      error: { status: "500", message: "boom" },
    });
    await act(async () => tableProps.onPageChange(3, tableProps.pageSize));
    await waitFor(() => expect(message.error).toHaveBeenCalledWith("boom"));
  });
});
