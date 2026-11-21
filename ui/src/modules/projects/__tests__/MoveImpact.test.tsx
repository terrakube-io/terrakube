import { render, screen } from "@testing-library/react";
import projectService from "../projectService";
import MoveImpact from "../MoveImpact";

jest.mock("../projectService", () => ({
  __esModule: true,
  default: {
    listProjectAccess: jest.fn(),
    listProjectPolicySets: jest.fn(),
    listCollectionsForProject: jest.fn(),
    listProjectDefaultTags: jest.fn(),
    listOrganizationTags: jest.fn(),
  },
}));

const service = projectService as jest.Mocked<typeof projectService>;

// The service returns richer types than these tests need, so the stubs are cast once, here.
const ok = (data: unknown) => ({ isError: false, data }) as never;

describe("MoveImpact", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    service.listOrganizationTags.mockResolvedValue([{ id: "t1", name: "env" }]);
    service.listProjectDefaultTags.mockImplementation(async (_org, project) =>
      project === "target" ? [{ id: "x", tagId: "t1", value: "prod" }] : []
    );
    service.listProjectAccess.mockImplementation(async (_org, project) =>
      ok(
        project === "target"
          ? [{ name: "payments-devs" }, { name: "shared" }]
          : [{ name: "platform" }, { name: "shared" }]
      )
    );
    service.listProjectPolicySets.mockImplementation(async (_org, project) =>
      ok(
        project === "target"
          ? [
              {
                id: "p1",
                name: "pwd-guardrails",
                enforcementLevel: "SOFT_MANDATORY",
                global: false,
                attachedToProject: true,
              },
            ]
          : [{ id: "g", name: "baseline", enforcementLevel: "HARD_MANDATORY", global: true, attachedToProject: false }]
      )
    );
    service.listCollectionsForProject.mockImplementation(async (_org, project) =>
      ok(
        project === "target"
          ? [{ id: "c1", name: "payments-common", priority: 1, variableCount: 2, referenceId: "r" }]
          : [{ id: "c1", name: "payments-common", priority: 1, variableCount: 2 }]
      )
    );
  });

  it("explains access, policy, variables and tags before anything is moved", async () => {
    render(
      <MoveImpact
        orgid="org"
        sourceProjectId="source"
        targetProjectId="target"
        targetName="payments-prod"
        workspaceCount={3}
      />
    );

    expect(
      await screen.findByText(/1 team gain access \(payments-devs\), 1 team lose access \(platform\)/)
    ).toBeInTheDocument();
    expect(screen.getByText("pwd-guardrails starts applying (Soft mandatory)")).toBeInTheDocument();
    expect(screen.getByText('Collection "payments-common" starts applying')).toBeInTheDocument();
    expect(screen.getByText("Will inherit env:prod")).toBeInTheDocument();
    expect(screen.getByText("Access granted directly on a workspace is kept.")).toBeInTheDocument();
  });

  it("does not claim access changes when the team lists cannot be read", async () => {
    service.listProjectAccess.mockResolvedValue({ isError: true, data: undefined } as never);

    render(
      <MoveImpact
        orgid="org"
        sourceProjectId="source"
        targetProjectId="target"
        targetName="payments-prod"
        workspaceCount={1}
      />
    );

    expect(await screen.findByText("You can see these projects but not who has access to them.")).toBeInTheDocument();
    expect(screen.queryByText(/gain access/)).not.toBeInTheDocument();
  });

  it("treats workspaces without a project as having nothing to lose", async () => {
    render(
      <MoveImpact
        orgid="org"
        sourceProjectId={null}
        targetProjectId="target"
        targetName="payments-prod"
        workspaceCount={1}
      />
    );

    expect(await screen.findByText(/2 teams gain access \(payments-devs, shared\)/)).toBeInTheDocument();
    expect(service.listProjectAccess).not.toHaveBeenCalledWith("org", "source");
  });

  it("says when nothing changes", async () => {
    service.listProjectAccess.mockResolvedValue(ok([]));
    service.listProjectPolicySets.mockResolvedValue(ok([]));
    service.listCollectionsForProject.mockResolvedValue(ok([]));
    service.listProjectDefaultTags.mockResolvedValue([]);

    render(
      <MoveImpact
        orgid="org"
        sourceProjectId="source"
        targetProjectId="target"
        targetName="payments-prod"
        workspaceCount={1}
      />
    );

    expect(await screen.findByText("Nothing changes for access, policy, variables or tags.")).toBeInTheDocument();
  });
});
