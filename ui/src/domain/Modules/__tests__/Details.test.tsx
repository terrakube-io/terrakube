import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ModuleDetails } from "../Details";

const permissions = { manageModule: false };

jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions, loading: false }),
}));
jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: (url: string) =>
      Promise.resolve({
        data: url.includes("/details")
          ? { submodules: [], variables: [], outputs: [], resources: [], readme: null }
          : {
              data: {
                id: "m1",
                type: "module",
                attributes: {
                  name: "network",
                  provider: "aws",
                  registryPath: "acme/network/aws",
                  latestVersion: "1.0.0",
                },
              },
              included: [{ id: "v1", type: "version", attributes: { version: "1.0.0" } }],
            },
      }),
    delete: jest.fn(),
    patch: jest.fn(),
  },
  getErrorMessage: (error: Error) => error.message,
}));

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/registry/m1"]}>
      <Routes>
        <Route path="/organizations/:orgid/registry/:id" element={<ModuleDetails organizationName="acme" />} />
      </Routes>
    </MemoryRouter>
  );

describe("ModuleDetails delete action", () => {
  it.each([
    [false, "true"],
    [true, "false"],
  ])("is disabled without manageModule (manageModule=%s)", async (allowed, ariaDisabled) => {
    permissions.manageModule = allowed;
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /Manage module/ }));

    const item = await screen.findByRole("menuitem", { name: /Delete module/ });
    expect(item.getAttribute("aria-disabled")).toBe(ariaDisabled);
    fireEvent.click(item);
    if (allowed) expect(await screen.findByRole("dialog")).toBeInTheDocument();
    else expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
