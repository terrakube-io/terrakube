import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { Registry } from "../Registry";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

const moduleItem = (id: string, name: string, description = "") => ({
  id,
  type: "module",
  attributes: { name, description, provider: "aws", latestVersion: "1.0.0", downloadQuantity: 3 },
});

const renderRegistry = (modules: unknown[]) => {
  (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
    Promise.resolve({
      data: url.includes("/module") ? { data: modules } : { data: { attributes: { name: "acme" } } },
    })
  );
  return render(
    <MemoryRouter initialEntries={["/organizations/org-1/registry"]}>
      <Routes>
        <Route
          path="/organizations/:orgid/registry"
          element={<Registry organizationName="acme" setOrganizationName={jest.fn()} />}
        />
      </Routes>
    </MemoryRouter>
  );
};

describe("Registry", () => {
  it("counts the modules that match the search", async () => {
    renderRegistry([moduleItem("m-1", "vpc", "Network"), moduleItem("m-2", "eks")]);

    expect(await screen.findByRole("heading", { name: "Modules (2)" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search modules"), { target: { value: "network" } });
    expect(screen.getByRole("heading", { name: "Modules (1)" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "vpc" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Search modules"), { target: { value: "nothing" } });
    expect(screen.getByText('No modules match "nothing".')).toBeInTheDocument();
  });

  it("offers to publish a module when the registry is empty", async () => {
    renderRegistry([]);

    expect(await screen.findByText(/There are no modules in acme yet/)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Modules \(/ })).not.toBeInTheDocument();
  });
});
