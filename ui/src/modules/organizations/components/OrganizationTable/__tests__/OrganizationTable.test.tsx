import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import OrganizationTable from "../OrganizationTable";
import { OrganizationModel } from "../../../types";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "@/config/actionTypes";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

jest.mock("@/modules/permissions/useOrgPermissions");
const mockUseOrgPermissions = useOrgPermissions as jest.Mock;

const organizations: OrganizationModel[] = [
  {
    id: "org-1",
    name: "acme-platform",
    description: "Core platform infra",
    executionMode: "Remote",
    workspaceCount: 14,
  },
  { id: "org-2", name: "data-eng", description: "Data lake and pipelines", executionMode: "Local", workspaceCount: 0 },
];

function renderTable(orgs = organizations) {
  return render(
    <MemoryRouter>
      <OrganizationTable organizations={orgs} />
    </MemoryRouter>
  );
}

describe("OrganizationTable", () => {
  beforeEach(() => {
    sessionStorage.clear();
    mockUseOrgPermissions.mockReturnValue({ permissions: { managePermission: true }, loading: false });
  });

  it("renders a row per organization with name, description and execution mode", () => {
    renderTable();
    expect(screen.getByText("acme-platform")).toBeInTheDocument();
    expect(screen.getByText("Core platform infra")).toBeInTheDocument();
    expect(screen.getByText("Remote")).toBeInTheDocument();
  });

  it("shows description as its own column, with a fallback for organizations without one", () => {
    renderTable([
      { id: "org-1", name: "acme-platform", description: "Core platform infra", executionMode: "Remote" },
      { id: "org-3", name: "no-desc-org", executionMode: "Local" },
    ]);
    expect(screen.getByText("Description")).toBeInTheDocument();
    expect(screen.getByText("No description set for this organization")).toBeInTheDocument();
  });

  it("shows the workspace count as a badge next to the name", () => {
    renderTable();
    expect(screen.getByText("14 workspaces")).toBeInTheDocument();
    expect(screen.getByText("0 workspaces")).toBeInTheDocument();
  });

  it("shows no status breakdown badges when workspaceStatusCounts is not provided", () => {
    const { container } = renderTable();
    expect(container.querySelectorAll(".organization-status-badge")).toHaveLength(0);
  });

  it("shows a compact status breakdown badge for each non-zero status, omitting zero counts", () => {
    const { container } = renderTable([
      {
        id: "org-1",
        name: "acme-platform",
        executionMode: "Remote",
        workspaceCount: 14,
        workspaceStatusCounts: { failed: 2, running: 1, completed: 10, waitingApproval: 0 },
      },
    ]);

    const badges = container.querySelectorAll(".organization-status-badge");
    expect(badges).toHaveLength(3);
    expect(container.querySelector('[title="Failed: 2"]')).toBeInTheDocument();
    expect(container.querySelector('[title="Running: 1"]')).toBeInTheDocument();
    expect(container.querySelector('[title="Completed: 10"]')).toBeInTheDocument();
    expect(container.querySelector('[title^="Awaiting approval"]')).not.toBeInTheDocument();
  });

  it("filters rows by search term against name and description", () => {
    renderTable();
    fireEvent.change(screen.getByPlaceholderText("Search organizations..."), { target: { value: "data" } });

    expect(screen.getByText("data-eng")).toBeInTheDocument();
    expect(screen.queryByText("acme-platform")).not.toBeInTheDocument();
  });

  it("stores organization id/name in sessionStorage on row click", () => {
    renderTable();
    fireEvent.click(screen.getByLabelText("Open organization acme-platform"));

    expect(sessionStorage.getItem(ORGANIZATION_ARCHIVE)).toBe("org-1");
    expect(sessionStorage.getItem(ORGANIZATION_NAME)).toBe("acme-platform");
  });

  it("renders a settings button per row when the user has permission", () => {
    renderTable();
    expect(screen.getAllByLabelText("organization settings")).toHaveLength(2);
  });

  it("clicking the settings button does not also trigger the row's navigation side effects", () => {
    renderTable();
    fireEvent.click(screen.getAllByLabelText("organization settings")[0]);
    expect(sessionStorage.getItem(ORGANIZATION_ARCHIVE)).toBeNull();
  });

  it("links to the organization workspaces page", () => {
    renderTable();
    expect(screen.getByLabelText("Open organization acme-platform")).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces"
    );
  });

  it("shows a Workspaces column header and sorts by workspace count when clicked", () => {
    const { container } = renderTable();
    expect(screen.getByText("Workspaces")).toBeInTheDocument();

    const rowText = () => Array.from(container.querySelectorAll(".organization-row")).map((r) => r.textContent);
    expect(rowText()[0]).toContain("acme-platform");

    fireEvent.click(screen.getByText("Workspaces"));

    expect(rowText()[0]).toContain("data-eng");
  });

  it("defaults to sorting by name ascending, and clicking the Name header toggles to descending", () => {
    const { container } = renderTable();
    const rowText = () => Array.from(container.querySelectorAll(".organization-row")).map((r) => r.textContent);

    expect(rowText()[0]).toContain("acme-platform");

    fireEvent.click(screen.getByText("Name"));

    expect(rowText()[0]).toContain("data-eng");
  });

  it("shows pagination", () => {
    const { container } = renderTable();
    expect(container.querySelector(".ant-pagination")).toBeInTheDocument();
  });
});
