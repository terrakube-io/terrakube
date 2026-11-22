import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { WorkspaceDetails } from "../Details";

window._env_ = {
  REACT_APP_AUTHORITY: "http://localhost/authority",
  REACT_APP_CLIENT_ID: "client-id",
  REACT_APP_REDIRECT_URI: "http://localhost/redirect",
  REACT_APP_SCOPE: "openid",
  REACT_APP_TERRAKUBE_API_URL: "http://localhost:8080/api/v1",
  REACT_APP_TERRAKUBE_VERSION: "test",
  REACT_APP_REGISTRY_URI: "http://localhost:8080/registry",
};

const getMock = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => getMock(...args),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

// Details.tsx imports from '../../types.js' and '../Icons.jsx' with file extensions.
// Virtual mocks allow Jest to resolve these ESM extension imports in the test environment
// without requiring edits to untouched shared source files.
jest.mock("../../types.js", () => jest.requireActual("../../types"), { virtual: true });
jest.mock("../Icons.jsx", () => jest.requireActual("../Icons"), { virtual: true });
jest.mock("../ResourceDrawer", () => ({ ResourceDrawer: () => null }));

jest.mock("../../../hooks", () => ({
  usePolling: jest.fn(),
  useJobStatusSubscription: jest.fn(),
}));

describe("WorkspaceDetails", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sessionStorage.clear();
  });

  it("renders correctly with Remote fallback when executionMode is null (e.g. CLI driven workflow)", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/workspace/ws-1")) {
        return Promise.resolve({
          data: {
            data: {
              id: "ws-1",
              type: "workspace",
              attributes: {
                name: "sample-workspace",
                description: "Test workspace",
                source:
                  "https://terrakube-api.localhost/remote/tfe/v2/configuration-versions/1/terraformContent.tar.gz",
                branch: "remote-content",
                executionMode: null,
                terraformVersion: "1.5.0",
                folder: "/",
              },
              relationships: {
                organization: { data: { id: "org-1" } },
              },
            },
            included: [
              {
                id: "org-1",
                type: "organization",
                attributes: { name: "test-org" },
              },
            ],
          },
        });
      }
      return Promise.resolve({ data: { data: [] } });
    });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/workspaces/ws-1"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/workspaces/:id"
            element={<WorkspaceDetails setOrganizationName={jest.fn()} setWorkspaceManageState={jest.fn()} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("CLI/API-driven workflow")).toBeInTheDocument();
    });

    expect(screen.getByText("Remote")).toBeInTheDocument();
  });

  it("requests the workspace with a fields entry for every included type", async () => {
    const workspace = {
      id: "ws-1",
      type: "workspace",
      attributes: { name: "sample-workspace", source: "", branch: "" },
    };
    getMock.mockImplementation((url: string) =>
      Promise.resolve({ data: { data: url.includes("/workspace/ws-1") ? workspace : [] } })
    );

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/workspaces/ws-1"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/workspaces/:id"
            element={<WorkspaceDetails setOrganizationName={jest.fn()} setWorkspaceManageState={jest.fn()} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() =>
      expect(getMock).toHaveBeenCalledWith(
        "organization/org-1/workspace/ws-1?include=job,variable,history,schedule,vcs,organization,reference,project" +
          "&fields[workspace]=allowRemoteApply,branch,createdBy,createdDate,defaultTemplate,deleted,description," +
          "executionMode,folder,globalRemoteState,iacType,lastJobDate,lastJobStatus,lockDescription,locked," +
          "moduleSshKey,name,policyComplianceStatus,requireApproveToApply,restrictApplyToConfiguredBranch,sharedIds," +
          "source,terraformVersion,updatedBy,updatedDate,access,agent,history,job,notificationConfiguration," +
          "organization,project,reference,runTriggers,schedule,sourceRunTriggers,ssh,variable,vcs,webhook,workspaceTag" +
          "&fields[job]=status,via,comments,createdBy,createdDate,updatedDate,commitId,templateReference,approvedBy," +
          "approvedAt,approvalTeam,prCommentError" +
          "&fields[variable]=key,value,category,hcl,sensitive,incomplete,description" +
          "&fields[history]=jobReference,output,createdBy,createdDate" +
          "&fields[schedule]=cron,templateReference" +
          "&fields[vcs]=vcsType" +
          "&fields[organization]=name" +
          "&fields[project]=name" +
          "&fields[reference]="
      )
    );
  });

  const renderOverviewWithRuns = async (
    runs: Array<{ id: string; status: string; planAdd?: number; planChange?: number; planDestroy?: number }>,
    workspaceAttributes: Record<string, unknown> = {}
  ) => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/workspace/ws-1")) {
        return Promise.resolve({
          data: {
            data: {
              id: "ws-1",
              type: "workspace",
              attributes: {
                name: "sample-workspace",
                description: "Test workspace",
                source: "https://github.com/example/repo.git",
                branch: "main",
                executionMode: "remote",
                terraformVersion: "1.5.0",
                iacType: "terraform",
                folder: "/",
                ...workspaceAttributes,
              },
              relationships: { organization: { data: { id: "org-1" } } },
            },
            included: [
              { id: "org-1", type: "organization", attributes: { name: "test-org" } },
              ...runs.map((run) => ({
                id: run.id,
                type: "job",
                attributes: {
                  status: run.status,
                  createdBy: "someone",
                  createdDate: "2026-10-19T10:00:00Z",
                  updatedDate: "2026-10-19T10:05:00Z",
                  via: "UI",
                  planAdd: run.planAdd,
                  planChange: run.planChange,
                  planDestroy: run.planDestroy,
                },
              })),
            ],
          },
        });
      }
      return Promise.resolve({ data: { data: [] } });
    });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/workspaces/ws-1"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/workspaces/:id"
            element={<WorkspaceDetails setOrganizationName={jest.fn()} setWorkspaceManageState={jest.fn()} />}
          />
        </Routes>
      </MemoryRouter>
    );
  };

  it("keeps the run waiting for approval in the card when newer runs queue behind it", async () => {
    await renderOverviewWithRuns([
      { id: "1", status: "completed" },
      { id: "2", status: "waitingApproval" },
      { id: "3", status: "pending" },
      { id: "4", status: "pending" },
    ]);

    const card = await screen.findByRole("article", { name: "Current run" });
    expect(card).toHaveTextContent("#2");
    expect(card).toHaveTextContent("2 more runs queued behind this one");
    expect(screen.getByRole("heading", { name: "Current run" })).toBeInTheDocument();
  });

  it("does not let a newer finished run replace the run waiting for approval", async () => {
    await renderOverviewWithRuns([
      { id: "2", status: "waitingApproval" },
      { id: "3", status: "completed" },
    ]);

    const card = await screen.findByRole("article", { name: "Current run" });
    expect(card).toHaveTextContent("#2");
    expect(card).not.toHaveTextContent("queued behind");
  });

  it("shows the newest run as the latest run when every run is finished", async () => {
    await renderOverviewWithRuns([
      { id: "1", status: "failed" },
      { id: "2", status: "completed" },
    ]);

    const card = await screen.findByRole("article", { name: "Latest run" });
    expect(card).toHaveTextContent("#2");
    expect(screen.getByRole("heading", { name: "Latest run" })).toBeInTheDocument();
  });

  it("shows a drifted workspace in the header, on the way to the plan that found it", async () => {
    await renderOverviewWithRuns([{ id: "1", status: "completed" }], {
      driftStatus: "drifted",
      driftFirstSeen: "2026-11-11T02:00:00Z",
      driftJobId: 9,
    });

    const tag = await screen.findByRole("link", { name: /^Drifted/ });
    expect(tag).toHaveAttribute("href", "/organizations/org-1/workspaces/ws-1/runs/9");
  });

  it("does not mark a workspace that has no drift", async () => {
    await renderOverviewWithRuns([{ id: "1", status: "completed" }], { driftStatus: "clean" });

    await screen.findByText("Latest run");
    expect(screen.queryByRole("link", { name: /^Drifted/ })).not.toBeInTheDocument();
  });

  it("shows what the latest run plans to change", async () => {
    await renderOverviewWithRuns([{ id: "1", status: "completed", planAdd: 3, planChange: 1, planDestroy: 0 }]);

    expect(await screen.findByText("3 to add, 1 to change, 0 to destroy")).toBeInTheDocument();
  });
});
