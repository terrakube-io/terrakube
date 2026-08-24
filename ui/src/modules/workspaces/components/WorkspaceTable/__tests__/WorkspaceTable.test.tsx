import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import WorkspaceTable from "../WorkspaceTable";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { JobStatus } from "@/domain/types";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

const workspaces: WorkspaceListItem[] = [
  {
    id: "ws-1",
    name: "billing-api-staging",
    description: "Handles invoice generation and payment webhooks",
    iacType: "terraform",
    source: "https://github.com/acme/billing",
    normalizedSource: "https://github.com/acme/billing",
    lastStatus: JobStatus.Running,
    lastRun: "2024-06-01T00:00:00.000Z",
    terraformVersion: "1.8.0",
    tags: ["tag-1", "tag-2"],
    projectId: "proj-1",
    projectName: "platform",
    locked: true,
  },
  {
    id: "ws-2",
    name: "auth-service-dev",
    iacType: "terraform",
    source: "",
    branch: "remote-content",
    lastStatus: JobStatus.Failed,
    terraformVersion: "1.9.2",
    locked: false,
  },
];

const defaultProps = {
  organizationId: "org-1",
  workspaces,
  onSelectProject: jest.fn(),
  sortOption: "status" as const,
  onSortChange: jest.fn(),
};

function renderTable(props = {}) {
  return render(
    <MemoryRouter>
      <WorkspaceTable {...defaultProps} {...props} />
    </MemoryRouter>
  );
}

describe("WorkspaceTable", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    defaultProps.onSelectProject.mockClear();
    (defaultProps.onSortChange as jest.Mock).mockClear();
  });

  it("renders a row per workspace with name and status, with a single header", () => {
    renderTable();
    expect(screen.getByText("billing-api-staging")).toBeInTheDocument();
    expect(screen.getByText("auth-service-dev")).toBeInTheDocument();
    expect(screen.getAllByText("Name")).toHaveLength(1);
  });

  it("renders the project as a chip on its own line beneath the name", () => {
    const { container } = renderTable();
    expect(screen.getByText("platform")).toBeInTheDocument();
    const line2 = container.querySelector(".workspace-name-line2");
    expect(line2).toContainElement(screen.getByText("platform"));
  });

  it("does not render the workspace description or tag pills in the row", () => {
    renderTable();
    expect(screen.queryByText("Handles invoice generation and payment webhooks")).not.toBeInTheDocument();
    expect(screen.queryByText("tag-1")).not.toBeInTheDocument();
    expect(document.querySelector(".workspace-name-line3")).not.toBeInTheDocument();
  });

  it("calls onSelectProject with the project id when the project chip is clicked, without navigating", () => {
    renderTable();
    fireEvent.click(screen.getByText("platform"));
    expect(defaultProps.onSelectProject).toHaveBeenCalledWith("proj-1");
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("shows a lock icon only for locked workspaces", () => {
    renderTable();
    expect(screen.getByLabelText("lock")).toBeInTheDocument();
  });

  it("shows a spinning sync icon for a running workspace", () => {
    const { container } = renderTable();
    const icon = container.querySelector(".workspace-status-icon .anticon-sync");
    expect(icon).toBeInTheDocument();
    expect(icon).toHaveClass("anticon-spin");
  });

  it("shows a non-spinning status icon for a non-running workspace", () => {
    const { container } = renderTable();
    const rows = container.querySelectorAll(".workspace-row");
    const failedRowIcon = rows[1].querySelector(".workspace-status-icon .anticon");
    expect(failedRowIcon).toBeInTheDocument();
    expect(failedRowIcon).not.toHaveClass("anticon-spin");
  });

  it("still shows a status icon for a workspace with no last run", () => {
    const { container } = renderTable({
      workspaces: [{ id: "ws-3", name: "never-run-ws", iacType: "terraform", source: "" }],
    });
    expect(container.querySelector(".workspace-status-icon .anticon")).toBeInTheDocument();
  });

  it("navigates to the workspace on row click", () => {
    renderTable();
    fireEvent.click(screen.getByText("billing-api-staging"));
    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1");
  });

  it("truncates the source with a title attribute holding the full path", () => {
    renderTable();
    const link = screen.getByText("acme/billing");
    expect(link).toHaveAttribute("title", "acme/billing");
  });

  it("shows pagination in flat mode", () => {
    const { container } = renderTable();
    expect(container.querySelector(".ant-pagination")).toBeInTheDocument();
  });

  it("stays on the current page when a background refresh supplies a new array with the same workspaces", () => {
    const manyWorkspaces: WorkspaceListItem[] = Array.from({ length: 25 }, (_, i) => ({
      id: `bulk-${i}`,
      name: `bulk-workspace-${i}`,
      iacType: "terraform",
      source: "",
    }));

    const { container, rerender } = renderTable({ workspaces: manyWorkspaces });

    fireEvent.click(screen.getByTitle("2"));
    expect(screen.getByText("bulk-workspace-20")).toBeInTheDocument();

    // Simulate a polling refresh: same workspaces, but a brand-new array reference.
    rerender(
      <MemoryRouter>
        <WorkspaceTable {...defaultProps} workspaces={[...manyWorkspaces]} />
      </MemoryRouter>
    );

    expect(screen.getByText("bulk-workspace-20")).toBeInTheDocument();
    expect(container.querySelector(".ant-pagination-item-active")).toHaveTextContent("2");
  });

  it("clamps to the last valid page when a refresh shrinks the workspace list below the current page", () => {
    const manyWorkspaces: WorkspaceListItem[] = Array.from({ length: 25 }, (_, i) => ({
      id: `bulk-${i}`,
      name: `bulk-workspace-${i}`,
      iacType: "terraform",
      source: "",
    }));

    const { rerender } = renderTable({ workspaces: manyWorkspaces });

    fireEvent.click(screen.getByTitle("2"));
    expect(screen.getByText("bulk-workspace-20")).toBeInTheDocument();

    const shrunkWorkspaces = manyWorkspaces.slice(0, 5);
    rerender(
      <MemoryRouter>
        <WorkspaceTable {...defaultProps} workspaces={shrunkWorkspaces} />
      </MemoryRouter>
    );

    expect(screen.getByText("bulk-workspace-0")).toBeInTheDocument();
    expect(screen.queryByText("bulk-workspace-20")).not.toBeInTheDocument();
  });

  it("clicking the Name header sorts ascending when not already active", () => {
    renderTable({ sortOption: "status" });
    fireEvent.click(screen.getByText("Name"));
    expect(defaultProps.onSortChange).toHaveBeenCalledWith("name_asc");
  });

  it("clicking the Name header again toggles to descending when already ascending", () => {
    renderTable({ sortOption: "name_asc" });
    fireEvent.click(screen.getByText("Name"));
    expect(defaultProps.onSortChange).toHaveBeenCalledWith("name_desc");
  });

  it("clicking the Status header applies the single status sort", () => {
    renderTable({ sortOption: "name_asc" });
    fireEvent.click(screen.getByText("Status"));
    expect(defaultProps.onSortChange).toHaveBeenCalledWith("status");
  });

  it("is keyboard-operable: Enter on a sortable header sorts, and on a row navigates", () => {
    renderTable({ sortOption: "status" });

    fireEvent.keyDown(screen.getByText("Name"), { key: "Enter" });
    expect(defaultProps.onSortChange).toHaveBeenCalledWith("name_asc");

    fireEvent.keyDown(screen.getByText("billing-api-staging"), { key: " " });
    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1");
  });

  describe("grouped mode", () => {
    const groups = [
      { key: "proj-1", label: "platform", items: [workspaces[0]] },
      { key: "__unassigned__", label: "(unassigned)", items: [workspaces[1]] },
    ];

    it("renders one header, a divider per group, and no pagination", () => {
      const { container } = renderTable({ groups });
      expect(screen.getAllByText("Name")).toHaveLength(1);
      const dividers = container.querySelectorAll(".workspace-group-divider");
      expect(dividers).toHaveLength(2);
      expect(dividers[0]).toHaveTextContent("platform");
      expect(dividers[0]).toHaveTextContent("1 workspace");
      expect(dividers[1]).toHaveTextContent("(unassigned)");
      expect(container.querySelector(".ant-pagination")).not.toBeInTheDocument();
    });

    it("renders every workspace across all groups", () => {
      renderTable({ groups });
      expect(screen.getByText("billing-api-staging")).toBeInTheDocument();
      expect(screen.getByText("auth-service-dev")).toBeInTheDocument();
    });

    it("caps a large group at 10 rows with a 'Show N more' link, which expands it", () => {
      const bigGroupItems: WorkspaceListItem[] = Array.from({ length: 12 }, (_, i) => ({
        id: `bulk-${i}`,
        name: `bulk-workspace-${i}`,
        iacType: "terraform",
        source: "",
      }));
      const bigGroups = [{ key: "proj-1", label: "platform", items: bigGroupItems }];

      renderTable({ groups: bigGroups });

      expect(screen.getByText("bulk-workspace-0")).toBeInTheDocument();
      expect(screen.getByText("bulk-workspace-9")).toBeInTheDocument();
      expect(screen.queryByText("bulk-workspace-10")).not.toBeInTheDocument();
      expect(screen.getByText("Show 2 more workspaces")).toBeInTheDocument();

      fireEvent.click(screen.getByText("Show 2 more workspaces"));

      expect(screen.getByText("bulk-workspace-10")).toBeInTheDocument();
      expect(screen.getByText("bulk-workspace-11")).toBeInTheDocument();
      expect(screen.getByText("Show less")).toBeInTheDocument();
    });
  });
});
