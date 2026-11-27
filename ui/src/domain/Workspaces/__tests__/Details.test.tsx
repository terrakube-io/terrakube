import { act, render, screen, waitFor } from "@testing-library/react";
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

  const WORKSPACE_FIELDS =
    "fields[workspace]=allowRemoteApply,branch,createdBy,createdDate,defaultTemplate,deleted,description," +
    "executionMode,folder,globalRemoteState,iacType,lastJobDate,lastJobStatus,lockDescription,locked,moduleSshKey," +
    "name,policyComplianceStatus,requireApproveToApply,restrictApplyToConfiguredBranch,sharedIds,source," +
    "terraformVersion,updatedBy,updatedDate,access,agent,history,job,notificationConfiguration,organization,project," +
    "reference,runTriggers,schedule,sourceRunTriggers,ssh,variable,vcs,webhook,workspaceTag";
  const BASE_FIELDS =
    `&${WORKSPACE_FIELDS}&fields[history]=jobReference,output,createdBy,createdDate&fields[vcs]=vcsType` +
    "&fields[organization]=name&fields[project]=name";
  const OVERVIEW_URL =
    `organization/org-1/workspace/ws-1?include=job,history,vcs,organization,project${BASE_FIELDS}` +
    "&fields[job]=status,via,comments,createdBy,createdDate,updatedDate,commitId,templateReference,approvedBy," +
    "approvedAt,approvalTeam,prCommentError";
  const VARIABLES_URL =
    `organization/org-1/workspace/ws-1?include=variable,reference,history,vcs,organization,project${BASE_FIELDS}` +
    "&fields[variable]=key,value,category,hcl,sensitive,incomplete,description&fields[reference]=";
  const SCHEDULES_URL =
    `organization/org-1/workspace/ws-1?include=schedule,history,vcs,organization,project${BASE_FIELDS}` +
    "&fields[schedule]=cron,templateReference";
  const BASE_URL = `organization/org-1/workspace/ws-1?include=history,vcs,organization,project${BASE_FIELDS}`;

  const workspaceResponse = (included: object[] = []) => ({
    data: {
      data: {
        id: "ws-1",
        type: "workspace",
        attributes: { name: "sample-workspace", source: "https://github.com/example/repo.git", branch: "main" },
        relationships: { organization: { data: { id: "org-1" } } },
      },
      included: [{ id: "org-1", type: "organization", attributes: { name: "test-org" } }, ...included],
    },
  });

  // Workspace requests stay pending until the test answers them.
  const pendingWorkspaceRequests = () => {
    const pending = new Map<string, (included?: object[]) => void>();
    getMock.mockImplementation((url: string) => {
      if (url.startsWith("organization/org-1/workspace/ws-1?")) {
        return new Promise((resolve) => pending.set(url, (included) => resolve(workspaceResponse(included))));
      }
      if (url === "organization/org-1/workspace/ws-1") return Promise.resolve(workspaceResponse());
      return Promise.resolve({ data: { data: [] } });
    });
    return async (url: string, included?: object[]) => {
      await waitFor(() => expect(pending.has(url)).toBe(true));
      await act(async () => {
        pending.get(url)!(included);
        pending.delete(url);
      });
    };
  };

  const renderTab = (selectedTab?: string) => {
    const tree = (tab?: string) => (
      <MemoryRouter initialEntries={["/organizations/org-1/workspaces/ws-1"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/workspaces/:id"
            element={
              <WorkspaceDetails setOrganizationName={jest.fn()} setWorkspaceManageState={jest.fn()} selectedTab={tab} />
            }
          />
        </Routes>
      </MemoryRouter>
    );
    const { rerender } = render(tree(selectedTab));
    return (tab: string) => rerender(tree(tab));
  };

  const run = { id: "7", type: "job", attributes: { status: "completed", createdBy: "someone", via: "UI" } };

  it("loads the runs with the overview and nothing the overview does not show", async () => {
    const answer = pendingWorkspaceRequests();
    renderTab();
    await answer(OVERVIEW_URL, [run]);
    expect(await screen.findByRole("article", { name: "Latest run" })).toHaveTextContent("#7");
    expect(getMock).not.toHaveBeenCalledWith("/organization/org-1/globalvar");
  });

  it.each([
    ["2", OVERVIEW_URL],
    ["3", BASE_URL],
    ["4", VARIABLES_URL],
    ["5", SCHEDULES_URL],
    ["6", BASE_URL],
    ["7", BASE_URL],
  ])("loads tab %s with its own collections", async (tab, url) => {
    const answer = pendingWorkspaceRequests();
    renderTab(tab);
    await answer(url);
    const workspaceRequests = getMock.mock.calls.map(([called]) => called).filter((called) => called.includes("?"));
    expect(workspaceRequests).toEqual([url]);
  });

  it("shows a spinner, not an empty run list, until a load with the runs lands", async () => {
    const answer = pendingWorkspaceRequests();
    const showTab = renderTab("4");
    await answer(VARIABLES_URL);
    expect(await screen.findByText(/Terraform Variables/)).toBeInTheDocument();

    showTab("2");
    await waitFor(() => expect(getMock).toHaveBeenCalledWith(OVERVIEW_URL));
    expect(screen.queryByText(/No runs yet/)).not.toBeInTheDocument();
    expect(document.querySelector(".ant-spin")).toBeInTheDocument();

    await answer(OVERVIEW_URL);
    expect(await screen.findByText(/No runs yet/)).toBeInTheDocument();
  });

  it("keeps the runs on screen while a tab without them is open and reloads them on return", async () => {
    const answer = pendingWorkspaceRequests();
    const showTab = renderTab("1");
    await answer(OVERVIEW_URL, [run]);
    expect(await screen.findByRole("article", { name: "Latest run" })).toHaveTextContent("#7");

    showTab("3");
    await answer(BASE_URL);
    showTab("1");
    expect(screen.getByRole("article", { name: "Latest run" })).toHaveTextContent("#7");
    await answer(OVERVIEW_URL, [run]);
  });

  const renderOverviewWithRuns = async (
    runs: Array<{ id: string; status: string; planAdd?: number; planChange?: number; planDestroy?: number }>,
    workspaceAttributes: Record<string, unknown> = {},
    project?: { id: string; name: string }
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
              relationships: {
                organization: { data: { id: "org-1" } },
                ...(project ? { project: { data: { id: project.id, type: "project" } } } : {}),
              },
            },
            included: [
              { id: "org-1", type: "organization", attributes: { name: "test-org" } },
              ...(project ? [{ id: project.id, type: "project", attributes: { name: project.name } }] : []),
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

  describe("the way back, from where the workspace was opened", () => {
    const payments = { id: "p-1", name: "payments" };
    const link = (name: string) => screen.getAllByRole("link", { name }).find((element) => element.closest("nav"));

    it("names the project and links to it when the workspace was opened from it", async () => {
      sessionStorage.setItem(
        "terrakube.workspaceOrigin",
        JSON.stringify({ workspaceId: "ws-1", origin: { kind: "project", path: "/organizations/org-1/projects/p-1" } })
      );
      await renderOverviewWithRuns([{ id: "1", status: "completed" }], {}, payments);

      await screen.findByText("Latest run");
      expect(link("payments")).toHaveAttribute("href", "/organizations/org-1/projects/p-1");
      expect(link("Projects")).toHaveAttribute("href", "/organizations/org-1/projects");
    });

    it("says Inbox when it was opened from the inbox", async () => {
      sessionStorage.setItem(
        "terrakube.workspaceOrigin",
        JSON.stringify({ workspaceId: "ws-1", origin: { kind: "inbox", path: "/inbox?kind=failed" } })
      );
      await renderOverviewWithRuns([{ id: "1", status: "completed" }], {}, payments);

      await screen.findByText("Latest run");
      expect(link("Inbox")).toHaveAttribute("href", "/inbox?kind=failed");
    });

    it("remembers the project when it was opened by a direct link, so the way back leads there", async () => {
      await renderOverviewWithRuns([{ id: "1", status: "completed" }], {}, payments);

      await screen.findByText("Latest run");
      await waitFor(() =>
        expect(JSON.parse(sessionStorage.getItem("terrakube.workspaceOrigin") as string)).toEqual({
          workspaceId: "ws-1",
          origin: { kind: "project", path: "/organizations/org-1/projects/p-1" },
        })
      );
    });

    it("ignores an origin that was remembered for another workspace", async () => {
      sessionStorage.setItem(
        "terrakube.workspaceOrigin",
        JSON.stringify({ workspaceId: "ws-other", origin: { kind: "inbox", path: "/inbox" } })
      );
      await renderOverviewWithRuns([{ id: "1", status: "completed" }]);

      await screen.findByText("Latest run");
      expect(screen.queryByRole("link", { name: "Inbox" })).not.toBeInTheDocument();
    });

    it("links to the project the workspace is in now when the remembered one is an old project", async () => {
      sessionStorage.setItem(
        "terrakube.workspaceOrigin",
        JSON.stringify({
          workspaceId: "ws-1",
          origin: { kind: "project", path: "/organizations/org-1/projects/p-old" },
        })
      );
      await renderOverviewWithRuns([{ id: "1", status: "completed" }], {}, { id: "p-new", name: "ledger" });

      await screen.findByText("Latest run");
      expect(link("ledger")).toHaveAttribute("href", "/organizations/org-1/projects/p-new");
      await waitFor(() =>
        expect(JSON.parse(sessionStorage.getItem("terrakube.workspaceOrigin") as string).origin.path).toBe(
          "/organizations/org-1/projects/p-new"
        )
      );
    });
  });
});
