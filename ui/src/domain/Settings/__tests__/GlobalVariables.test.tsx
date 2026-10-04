import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { GlobalVariablesSettings } from "../GlobalVariables";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), patch: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
  isPermissionError: () => false,
}));

const variable = (id: string, key: string, category: string, extra: Record<string, unknown> = {}) => ({
  id,
  attributes: { key, value: `${key}-value`, category, hcl: false, sensitive: false, description: "", ...extra },
});

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/settings/variables"]}>
      <Routes>
        <Route path="/organizations/:orgid/settings/variables" element={<GlobalVariablesSettings />} />
      </Routes>
    </MemoryRouter>
  );

describe("GlobalVariablesSettings", () => {
  beforeEach(() => jest.clearAllMocks());

  it("groups variables by category with counts and hides sensitive values", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: {
        data: [
          variable("v-1", "region", "TERRAFORM"),
          variable("v-2", "TOKEN", "ENV", { sensitive: true, value: undefined }),
        ],
      },
    });
    renderPage();

    expect(await screen.findByRole("heading", { name: "Terraform variables (1)" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Environment variables (1)" })).toBeInTheDocument();
    expect(screen.getByText("region-value")).toBeInTheDocument();
    expect(screen.getByText("Sensitive, write-only")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete variable TOKEN" })).toBeInTheDocument();
  });

  it("shows one empty state when there are no variables", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [] } });
    renderPage();

    expect(await screen.findByText("No global variables yet.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
