import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import OrgSettingsButton from "../OrgSettingsButton";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

jest.mock("@/modules/permissions/useOrgPermissions");
const mockUseOrgPermissions = useOrgPermissions as jest.Mock;

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

function renderButton() {
  return render(
    <MemoryRouter>
      <OrgSettingsButton orgId="org-1" />
    </MemoryRouter>
  );
}

describe("OrgSettingsButton", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
  });

  it("renders nothing while permissions are loading", () => {
    mockUseOrgPermissions.mockReturnValue({ permissions: {}, loading: true });
    const { container } = renderButton();
    expect(container.firstChild).toBeNull();
  });

  it("renders nothing when the user lacks managePermission", () => {
    mockUseOrgPermissions.mockReturnValue({ permissions: { managePermission: false }, loading: false });
    const { container } = renderButton();
    expect(container.firstChild).toBeNull();
  });

  it("renders the settings icon button when the user has managePermission", () => {
    mockUseOrgPermissions.mockReturnValue({ permissions: { managePermission: true }, loading: false });
    renderButton();
    expect(screen.getByLabelText("organization settings")).toBeInTheDocument();
  });

  it("navigates to the organization's settings page on click without bubbling", () => {
    mockUseOrgPermissions.mockReturnValue({ permissions: { managePermission: true }, loading: false });
    renderButton();
    fireEvent.click(screen.getByLabelText("organization settings"));
    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/settings");
  });
});
