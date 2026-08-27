import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import OrgSettingsButton from "../OrgSettingsButton";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

jest.mock("@/modules/permissions/useOrgPermissions");
const mockUseOrgPermissions = useOrgPermissions as jest.Mock;

function renderButton() {
  return render(
    <MemoryRouter>
      <OrgSettingsButton orgId="org-1" />
    </MemoryRouter>
  );
}

describe("OrgSettingsButton", () => {
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

  it("links to the organization's settings page", () => {
    mockUseOrgPermissions.mockReturnValue({ permissions: { managePermission: true }, loading: false });
    renderButton();
    expect(screen.getByLabelText("organization settings").closest("a")).toHaveAttribute(
      "href",
      "/organizations/org-1/settings"
    );
  });
});
