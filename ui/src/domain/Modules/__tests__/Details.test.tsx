import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ModuleDetails } from "../Details";

const permissions = { manageModule: false };
const mockModuleAttributes: Record<string, unknown> = {};
const mockPost = jest.fn(() => Promise.resolve({}));

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
                  ...mockModuleAttributes,
                },
              },
              included: [{ id: "v1", type: "version", attributes: { version: "1.0.0" } }],
            },
      }),
    delete: jest.fn(),
    patch: jest.fn(),
    post: (...args: unknown[]) => mockPost(...(args as [])),
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

describe("ModuleDetails version refresh", () => {
  afterEach(() => {
    Object.keys(mockModuleAttributes).forEach((key) => delete mockModuleAttributes[key]);
    mockPost.mockClear();
  });

  it("shows why the last refresh failed", async () => {
    mockModuleAttributes.lastRefreshError = "auth: https://github.com/acme/network.git: not authorized";
    renderPage();

    expect(await screen.findByText("Could not read versions from the repository")).toBeInTheDocument();
    expect(screen.getByText("auth: https://github.com/acme/network.git: not authorized")).toBeInTheDocument();
  });

  it("shows no warning when the last refresh worked", async () => {
    mockModuleAttributes.lastRefresh = new Date().toISOString();
    renderPage();

    expect(await screen.findByText("Last checked")).toBeInTheDocument();
    expect(screen.queryByText("Could not read versions from the repository")).not.toBeInTheDocument();
  });

  it("queues a version check through the sync endpoint", async () => {
    permissions.manageModule = true;
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Check for new versions" }));

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("https://terrakube-api.test/module/v1/organization/org-1/module/m1/sync")
    );
  });

  it("offers the version check only with manageModule", async () => {
    permissions.manageModule = false;
    renderPage();

    expect(await screen.findByText("Last checked")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Check for new versions" })).not.toBeInTheDocument();
  });
});
