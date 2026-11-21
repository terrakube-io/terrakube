import { act, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { loadInbox } from "@/modules/inbox/inboxService";
import { usePolling } from "@/hooks";
import projectService from "@/modules/projects/projectService";
import { loadOrgOverview } from "../orgOverviewService";
import OrgOverviewPage from "../OrgOverviewPage";

jest.mock("../orgOverviewService", () => ({
  ...jest.requireActual("../orgOverviewService"),
  loadOrgOverview: jest.fn(),
}));
jest.mock("@/modules/inbox/inboxService");
jest.mock("@/modules/projects/projectService", () => ({
  __esModule: true,
  default: { listProjectSummaries: jest.fn() },
}));
jest.mock("@/hooks/useOrganizationName", () => ({ useOrganizationName: () => "aws" }));
jest.mock("@/hooks", () => ({ usePolling: jest.fn() }));
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: ({ title, subTitle, children }: any) => (
    <div>
      <h1>{title}</h1>
      <p>{subTitle}</p>
      {children}
    </div>
  ),
}));

const summary = (over: object) => ({
  id: "p1",
  name: "Payments",
  workspaceCount: 5,
  completedCount: 3,
  failedCount: 1,
  runningCount: 0,
  queuedCount: 0,
  waitingApprovalCount: 0,
  neverRunCount: 1,
  nonCompliantCount: 0,
  attentionCount: 2,
  ...over,
});

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/overview"]}>
      <Routes>
        <Route path="/organizations/:id/overview" element={<OrgOverviewPage />} />
      </Routes>
    </MemoryRouter>
  );

describe("OrgOverviewPage", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    (loadOrgOverview as jest.Mock).mockResolvedValue({
      counts: {
        workspaces: 29,
        status: { completed: 16, failed: 4, waiting: 1, never: 6 },
        projects: 6,
        compliant: 20,
        nonCompliant: 4,
      },
      recent: [{ workspaceId: "w9", name: "svc-17", status: "completed", at: "2026-11-14T10:00:00Z" }],
    });
    (loadInbox as jest.Mock).mockResolvedValue({
      counts: { waiting: 1, failed: 4, drifted: 1, policy: 2 },
      failedOrganizations: [],
      items: [
        {
          key: "waiting:w1",
          kind: "waiting",
          orgId: "org-1",
          orgName: "aws",
          workspaceId: "w1",
          name: "sample_rds",
          projectName: "Platform",
          at: "2026-11-14T10:00:00Z",
        },
        {
          key: "failed:w2",
          kind: "failed",
          orgId: "org-1",
          orgName: "aws",
          workspaceId: "w2",
          name: "svc-02",
          at: "2026-11-14T09:00:00Z",
        },
      ],
    });
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue({
      isError: false,
      data: { items: [summary({})], totalRecords: 1 },
    });
  });

  it("leads with the health of the whole organization, then what needs a person", async () => {
    renderPage();

    expect(await screen.findByText("29 workspaces in 6 projects")).toBeInTheDocument();
    expect(
      screen.getByRole("img", { name: /16 completed, 4 failed, 1 waiting approval, 2 running or queued/ })
    ).toBeInTheDocument();
    expect(screen.getByText("83% compliant")).toBeInTheDocument();
    expect(screen.getByText("Failed runs").previousSibling).toHaveTextContent("4");
    expect(screen.getByRole("link", { name: "sample_rds" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/w1"
    );
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Payments" })).toHaveAttribute("href", "/organizations/org-1/projects/p1");
    expect(screen.getByRole("link", { name: "svc-17" })).toBeInTheDocument();
  });

  it("lists the sections in the order of the questions: how is it, what needs me, how are the projects", async () => {
    renderPage();
    await screen.findByText("29 workspaces in 6 projects");

    const order = ["Workspace health", "Needs you", "Projects"].map((name) => screen.getByLabelText(name));
    expect(order[0].compareDocumentPosition(order[1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(order[1].compareDocumentPosition(order[2]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("says how many projects are not shown and links to all of them", async () => {
    renderPage();
    await screen.findByText("29 workspaces in 6 projects");

    expect(screen.getByText(/1 of 6, the ones that need attention most/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All 6 projects" })).toHaveAttribute(
      "href",
      "/organizations/org-1/projects"
    );
    expect(screen.getByRole("link", { name: /\+5 more/ })).toHaveAttribute("href", "/organizations/org-1/projects");
  });

  it("asks for the projects with the most attention first", async () => {
    renderPage();
    await screen.findByText("29 workspaces in 6 projects");

    expect((projectService.listProjectSummaries as jest.Mock).mock.calls[0][1]).toMatchObject({
      sort: "-attentionCount,+name",
      pageSize: 5,
    });
  });

  it("says what needs you could not be read instead of saying nothing needs you", async () => {
    (loadInbox as jest.Mock).mockResolvedValue({
      counts: { waiting: 0, failed: 0, drifted: 0, policy: 0 },
      failedOrganizations: ["aws"],
      items: [],
    });

    renderPage();

    expect(await screen.findByText("What needs you could not be read.")).toBeInTheDocument();
    expect(screen.queryByText("Nothing needs you.")).not.toBeInTheDocument();
  });

  it("shows the newest answer when an older, slower load finishes last", async () => {
    const overview = (workspaces: number) => ({
      counts: {
        workspaces,
        status: { completed: 1, failed: 0, waiting: 0, never: 0 },
        projects: 2,
        compliant: 1,
        nonCompliant: 0,
      },
      recent: [],
    });
    let finishOlder: (value: unknown) => void = () => {};
    (loadOrgOverview as jest.Mock)
      .mockImplementationOnce(() => new Promise((resolve) => (finishOlder = resolve)))
      .mockResolvedValueOnce(overview(31));
    (usePolling as jest.Mock).mockImplementation(() => undefined);

    renderPage();
    const reload = (usePolling as jest.Mock).mock.calls[0][0] as () => Promise<void>;
    await act(async () => {
      await reload();
    });
    expect(await screen.findByText("31 workspaces in 2 projects")).toBeInTheDocument();

    await act(async () => finishOlder(overview(29)));

    expect(screen.getByText("31 workspaces in 2 projects")).toBeInTheDocument();
    expect(screen.queryByText("29 workspaces in 2 projects")).not.toBeInTheDocument();
  });

  it("says so when it cannot load", async () => {
    (loadOrgOverview as jest.Mock).mockRejectedValue(new Error("down"));
    renderPage();

    expect(await screen.findByText("Could not load the overview.")).toBeInTheDocument();
  });

  it("says which team owns a project, when it has one", async () => {
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue({
      isError: false,
      data: { items: [summary({ ownerTeam: "payments-devs" }), summary({ id: "p2", name: "Data" })], totalRecords: 2 },
    });
    renderPage();

    expect(await screen.findByText("5 workspaces · Owner payments-devs")).toBeInTheDocument();
    expect(screen.getAllByText(/^5 workspaces/)).toHaveLength(2);
  });
});
