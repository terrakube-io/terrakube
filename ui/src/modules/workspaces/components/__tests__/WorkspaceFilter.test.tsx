import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import WorkspaceFilter from "../WorkspaceFilter";
import organizationService from "@/modules/organizations/organizationService";

jest.mock("@/modules/organizations/organizationService", () => ({
  __esModule: true,
  default: {
    listOrganizationTags: jest.fn().mockResolvedValue({ isError: false, data: [] }),
  },
}));

const mockListOrganizationTags = organizationService.listOrganizationTags as jest.Mock;

const baseProps = {
  organizationId: "org-1",
  status: "All",
  onStatusChange: jest.fn(),
  search: "",
  onSearchChange: jest.fn(),
  tagIds: [] as string[],
  onTagIdsChange: jest.fn(),
  projectId: null as string | null,
  onProjectIdChange: jest.fn(),
  groupByProject: true,
  onGroupByProjectChange: jest.fn(),
  onTagsLoaded: jest.fn(),
  sortOption: "name_asc" as const,
  onSortChange: jest.fn(),
};

describe("WorkspaceFilter", () => {
  beforeEach(() => {
    Object.values(baseProps).forEach((v) => {
      if (typeof v === "function") (v as jest.Mock).mockClear?.();
    });
    mockListOrganizationTags.mockResolvedValue({ isError: false, data: [] });
  });

  it("does not apply the compact class by default", () => {
    const { container } = render(<WorkspaceFilter {...baseProps} />);
    expect(container.querySelector(".workspace-filter-container--compact")).not.toBeInTheDocument();
  });

  it("applies the compact class when compact is true", () => {
    const { container } = render(<WorkspaceFilter {...baseProps} compact />);
    expect(container.querySelector(".workspace-filter-container--compact")).toBeInTheDocument();
  });

  it("legacy mode (compact=false) renders the project dropdown, not chips", () => {
    render(<WorkspaceFilter {...baseProps} projects={[{ id: "p1", name: "platform" }]} />);
    expect(screen.getByText("Project")).toBeInTheDocument();
    expect(screen.queryByText("All projects")).not.toBeInTheDocument();
  });

  it("compact mode renders project chips and a group-by-project switch instead of the dropdown", () => {
    render(<WorkspaceFilter {...baseProps} compact projects={[{ id: "p1", name: "platform" }]} />);
    expect(screen.getByText("All projects")).toBeInTheDocument();
    expect(screen.getByText("platform")).toBeInTheDocument();
    expect(screen.getByText("Group by project")).toBeInTheDocument();
    expect(screen.queryByText("Project")).not.toBeInTheDocument();
  });

  it("clicking a project chip calls onProjectIdChange with that project's id", () => {
    render(<WorkspaceFilter {...baseProps} compact projects={[{ id: "p1", name: "platform" }]} />);
    fireEvent.click(screen.getByText("platform"));
    expect(baseProps.onProjectIdChange).toHaveBeenCalledWith("p1");
  });

  it("filters project chips by the project search box, keeping All projects and (unassigned) visible", () => {
    render(
      <WorkspaceFilter
        {...baseProps}
        compact
        projects={[
          { id: "p1", name: "platform" },
          { id: "p2", name: "billing-project" },
        ]}
      />
    );

    fireEvent.change(screen.getByPlaceholderText("Search projects..."), { target: { value: "bill" } });

    expect(screen.getByText("billing-project")).toBeInTheDocument();
    expect(screen.queryByText("platform")).not.toBeInTheDocument();
    expect(screen.getByText("All projects")).toBeInTheDocument();
    expect(screen.getByText("(unassigned)")).toBeInTheDocument();
  });

  it("toggling the group-by-project switch calls onGroupByProjectChange", () => {
    render(<WorkspaceFilter {...baseProps} compact groupByProject={true} />);
    fireEvent.click(screen.getByRole("switch"));
    expect(baseProps.onGroupByProjectChange).toHaveBeenCalledWith(false);
  });

  it("calls onStatusChange when a status segment is clicked", () => {
    render(<WorkspaceFilter {...baseProps} />);
    fireEvent.click(screen.getByText("Failed"));
    expect(baseProps.onStatusChange).toHaveBeenCalledWith("failed");
  });

  it("shows a removable chip for each active tag filter, and removing one calls onTagIdsChange without it", async () => {
    mockListOrganizationTags.mockResolvedValue({
      isError: false,
      data: [{ id: "tag-1", attributes: { name: "billing" } }],
    });
    render(<WorkspaceFilter {...baseProps} compact tagIds={["tag-1"]} />);

    await waitFor(() => expect(screen.getByText("billing")).toBeInTheDocument());

    const closeIcon = document.querySelector(".ant-tag-close-icon");
    expect(closeIcon).not.toBeNull();
    fireEvent.click(closeIcon!);

    expect(baseProps.onTagIdsChange).toHaveBeenCalledWith([]);
  });

  it("legacy mode commits search on Enter, not on every keystroke", () => {
    render(<WorkspaceFilter {...baseProps} />);
    const input = screen.getByPlaceholderText("Search by name...");
    fireEvent.change(input, { target: { value: "billing" } });
    expect(baseProps.onSearchChange).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: "Enter", code: "Enter" });
    expect(baseProps.onSearchChange).toHaveBeenCalledWith("billing");
  });

  it("compact mode filters live as you type, without needing Enter", () => {
    render(<WorkspaceFilter {...baseProps} compact />);
    const input = screen.getByPlaceholderText("Search by name...");
    fireEvent.change(input, { target: { value: "billing" } });
    expect(baseProps.onSearchChange).toHaveBeenCalledWith("billing");
  });
});
