import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { GeneralSettings } from "../General";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), patch: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
  isPermissionError: () => false,
}));
jest.mock("@/modules/organizations/useOrganizationSummaries", () => ({
  useOrganizationSummaries: () => ({ removeOrganization: jest.fn(), updateOrganization: jest.fn() }),
}));
jest.mock("@/hooks/useOrganizationName", () => ({ cacheOrganizationName: jest.fn() }));
jest.mock("@/components/settings/ManagedInCodeBanner", () => ({ ManagedInCodeBanner: () => null }));

const get = axiosInstance.get as jest.Mock;
const patch = axiosInstance.patch as jest.Mock;

const organization = (attributes: object = {}) => ({
  data: {
    data: {
      id: "org-1",
      attributes: { name: "aws", description: "d", executionMode: "remote", icon: "cloud:#1677ff", ...attributes },
    },
  },
});

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/settings/general"]}>
      <Routes>
        <Route path="/organizations/:orgid/settings/general" element={<GeneralSettings managePermission />} />
      </Routes>
    </MemoryRouter>
  );

const save = async () => {
  fireEvent.click(await screen.findByRole("button", { name: "Update organization" }));
};

const sentAttributes = () => patch.mock.calls[0][1].data.attributes;

describe("organization General settings, managed in code", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    patch.mockResolvedValue({ status: 204 });
  });

  it("sends the repository trimmed, with the mode, when one is set", async () => {
    get.mockResolvedValue(
      organization({ managedInCodeRepository: "  https://github.com/example/tf  ", managedInCodeMode: "readonly" })
    );
    renderPage();

    await save();

    await waitFor(() => expect(patch).toHaveBeenCalled());
    expect(sentAttributes()).toMatchObject({
      managedInCodeRepository: "https://github.com/example/tf",
      managedInCodeMode: "readonly",
    });
  });

  it("defaults the mode to warn for a repository that has none", async () => {
    get.mockResolvedValue(organization({ managedInCodeRepository: "https://github.com/example/tf" }));
    renderPage();

    await save();

    await waitFor(() => expect(patch).toHaveBeenCalled());
    expect(sentAttributes().managedInCodeMode).toBe("warn");
  });

  it("clears the repository and the mode together when the repository is blank, never sending an empty mode", async () => {
    get.mockResolvedValue(organization({ managedInCodeRepository: "   ", managedInCodeMode: "readonly" }));
    renderPage();

    await save();

    await waitFor(() => expect(patch).toHaveBeenCalled());
    expect(sentAttributes()).toMatchObject({ managedInCodeRepository: null, managedInCodeMode: null });
  });

  it("refuses an address that is not http or https, so it is not saved without the link it would lose", async () => {
    get.mockResolvedValue(organization({ managedInCodeRepository: "github.com/example/tf" }));
    renderPage();

    await save();

    expect(await screen.findByText("Enter an http or https address")).toBeInTheDocument();
    expect(patch).not.toHaveBeenCalled();
  });
});
