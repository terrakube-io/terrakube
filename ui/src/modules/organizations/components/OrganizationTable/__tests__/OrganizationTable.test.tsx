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

  it("shows the workspace count as a badge next to the name", () => {
    renderTable();
    expect(screen.getByText("14 workspaces")).toBeInTheDocument();
    expect(screen.getByText("0 workspaces")).toBeInTheDocument();
  });

  it("filters rows by search term against name and description", () => {
    renderTable();
    fireEvent.change(screen.getByPlaceholderText("Search organizations..."), { target: { value: "data" } });

    expect(screen.getByText("data-eng")).toBeInTheDocument();
    expect(screen.queryByText("acme-platform")).not.toBeInTheDocument();
  });

  it("stores organization id/name in sessionStorage on row click", () => {
    renderTable();
    fireEvent.click(screen.getByText("acme-platform"));

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
});
