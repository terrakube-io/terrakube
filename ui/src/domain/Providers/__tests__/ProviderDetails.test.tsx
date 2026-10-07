import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ProviderDetails } from "../ProviderDetails";

const permissions = { manageProvider: false };

jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions, loading: false }),
}));
jest.mock("../providerService", () => ({
  getProvider: () =>
    Promise.resolve({
      data: {
        id: "p1",
        type: "provider",
        attributes: { name: "random", description: "", registryNamespace: "hashicorp" },
      },
      included: [{ id: "v1", type: "version", attributes: { versionNumber: "3.6.0", protocols: "5.0" } }],
    }),
  deleteProviderCascade: jest.fn(),
  updateVersionStatus: jest.fn(),
}));

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/registry/providers/p1"]}>
      <Routes>
        <Route
          path="/organizations/:orgid/registry/providers/:providerid"
          element={<ProviderDetails organizationName="acme" />}
        />
      </Routes>
    </MemoryRouter>
  );

describe("ProviderDetails delete action", () => {
  it.each([
    [false, "true"],
    [true, "false"],
  ])("is disabled without manageProvider (manageProvider=%s)", async (allowed, ariaDisabled) => {
    permissions.manageProvider = allowed;
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /Manage provider/ }));

    const item = await screen.findByRole("menuitem", { name: /Delete provider/ });
    expect(item.getAttribute("aria-disabled")).toBe(ariaDisabled);
    fireEvent.click(item);
    if (allowed) expect(await screen.findByRole("dialog")).toBeInTheDocument();
    else expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
