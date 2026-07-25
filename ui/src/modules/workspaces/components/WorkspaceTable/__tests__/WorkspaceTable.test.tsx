import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import WorkspaceTable from "../WorkspaceTable";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { TagModel } from "@/modules/organizations/types";
import { JobStatus } from "@/domain/types";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

const tags: TagModel[] = [
  { id: "tag-1", name: "billing" },
  { id: "tag-2", name: "critical" },
  { id: "tag-3", name: "quarterly" },
  { id: "tag-4", name: "extra" },
];

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
    tags: ["tag-1", "tag-2", "tag-3", "tag-4"],
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
  tags,
  onToggleTag: jest.fn(),
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
    defaultProps.onToggleTag.mockClear();
    defaultProps.onSelectProject.mockClear();
    (defaultProps.onSortChange as jest.Mock).mockClear();
  });

  it("renders a row per workspace with name and status, with a single header", () => {
    renderTable();
    expect(screen.getByText("billing-api-staging")).toBeInTheDocument();
    expect(screen.getByText("auth-service-dev")).toBeInTheDocument();
    expect(screen.getAllByText("Name")).toHaveLength(1);
  });

  it("renders visible tags as pills, up to the cap", () => {
    renderTable();
    expect(screen.getByText("billing")).toBeInTheDocument();
    expect(screen.getByText("critical")).toBeInTheDocument();
    expect(screen.getByText("quarterly")).toBeInTheDocument();
  });

  it("caps visible tags at 3 and shows a +N badge with the rest in its title", () => {
    renderTable();
    expect(screen.queryByText("extra")).not.toBeInTheDocument();
    const overflow = screen.getByText("+1");
    expect(overflow).toHaveAttribute("title", "extra");
  });

  it("clicking the +N overflow badge does not navigate", () => {
    renderTable();
    fireEvent.click(screen.getByText("+1"));
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("renders the description, truncated with a title attribute holding the full text", () => {
    renderTable();
    const desc = screen.getByText("Handles invoice generation and payment webhooks");
    expect(desc).toHaveAttribute("title", "Handles invoice generation and payment webhooks");
  });

  it("calls onToggleTag with the tag id when a tag pill is clicked, without navigating", () => {
    renderTable();
    fireEvent.click(screen.getByText("billing"));
    expect(defaultProps.onToggleTag).toHaveBeenCalledWith("tag-1");
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("calls onSelectProject with the project id when the project tag is clicked, without navigating", () => {
    renderTable();
    fireEvent.click(screen.getByText("platform"));
    expect(defaultProps.onSelectProject).toHaveBeenCalledWith("proj-1");
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("shows a lock icon only for locked workspaces", () => {
    renderTable();
    expect(screen.getByLabelText("lock")).toBeInTheDocument();
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
