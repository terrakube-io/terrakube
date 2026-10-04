import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { TemplatesSettings } from "../Templates";
import { EditTemplate } from "../EditTemplate";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: Error) => err.message,
  isPermissionError: () => false,
}));
jest.mock("@/components/forms/CodeEditor", () => ({ CodeEditor: () => null }));

const renderList = (managePermission = true) =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/settings/templates"]}>
      <Routes>
        <Route
          path="/organizations/:orgid/settings/templates"
          element={<TemplatesSettings managePermission={managePermission} />}
        />
      </Routes>
    </MemoryRouter>
  );

describe("TemplatesSettings", () => {
  beforeEach(() => jest.clearAllMocks());

  it("lists templates under a count heading with named icon actions", async () => {
    jest.mocked(axiosInstance.get).mockResolvedValue({
      data: { data: [{ id: "t-1", attributes: { name: "Plan and apply", description: "Runs plan and apply" } }] },
    });
    jest.mocked(axiosInstance.delete).mockResolvedValue({});
    renderList();

    expect(await screen.findByRole("heading", { name: "Templates (1)" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Plan and apply" })).toHaveAttribute(
      "href",
      "/organizations/org-1/settings/templates/edit/t-1"
    );

    fireEvent.click(screen.getByRole("button", { name: "Delete Plan and apply" }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete template" }));
    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/template/t-1"));
  });

  it("shows an empty state with one action instead of an empty list", async () => {
    jest.mocked(axiosInstance.get).mockResolvedValue({ data: { data: [] } });
    renderList();

    expect(await screen.findByText("No templates yet. Workspaces need one to run a job.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Templates \(/ })).not.toBeInTheDocument();
    // The header primary plus the empty-state action.
    expect(screen.getAllByRole("link", { name: /Create template/ })).toHaveLength(2);
  });

  it("shows template names as plain text without the manage permission", async () => {
    jest.mocked(axiosInstance.get).mockResolvedValue({
      data: { data: [{ id: "t-1", attributes: { name: "Plan and apply" } }] },
    });
    renderList(false);

    expect(await screen.findByText("Plan and apply")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Plan and apply" })).not.toBeInTheDocument();
  });

  it("disables updating and deleting a template without the manage permission", async () => {
    jest.mocked(axiosInstance.get).mockResolvedValue({
      data: { data: { id: "t-1", attributes: { name: "Plan and apply", tcl: btoa("flow: []") } } },
    });
    render(
      <MemoryRouter>
        <EditTemplate setMode={jest.fn()} templateId="t-1" loadTemplates={jest.fn()} managePermission={false} />
      </MemoryRouter>
    );

    expect(await screen.findByRole("button", { name: "Update template" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete this template" })).toBeDisabled();
  });
});
