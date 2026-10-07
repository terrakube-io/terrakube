import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { VariableCollectionsSettings } from "../VariableCollections";
import { CreateEditCollection } from "../CreateEditCollection";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), patch: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

const collection = { id: "col-1", attributes: { name: "aws-credentials", description: "AWS settings", priority: 20 } };
const variable = {
  id: "item-1",
  attributes: { key: "AWS_REGION", value: "eu-west-1", category: "ENV", hcl: false, sensitive: false },
};

const mockGet = (collections: unknown[], items: unknown[] = []) =>
  (axiosInstance.get as jest.Mock).mockImplementation((url: string) => {
    if (url.endsWith("/item")) return Promise.resolve({ data: { data: items } });
    if (url.endsWith("/reference")) return Promise.resolve({ data: { data: [] } });
    if (url.endsWith("/workspace")) return Promise.resolve({ data: { data: [] } });
    if (/\/collection\/col-\d+$/.test(url)) return Promise.resolve({ data: { data: collection } });
    return Promise.resolve({ data: { data: collections } });
  });

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/organizations/:orgid/settings/collection" element={<VariableCollectionsSettings />} />
        <Route path="/organizations/:orgid/settings/collection/new" element={<CreateEditCollection mode="create" />} />
        <Route
          path="/organizations/:orgid/settings/collection/edit/:collectionid"
          element={<CreateEditCollection mode="edit" />}
        />
      </Routes>
    </MemoryRouter>
  );

describe("VariableCollectionsSettings", () => {
  beforeEach(() => jest.clearAllMocks());

  it("shows an empty state with one create action", async () => {
    mockGet([]);
    renderAt("/organizations/org-1/settings/collection");

    expect(await screen.findByText(/no variable collections in this organization/)).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /Create variable collection/ })).toHaveLength(1);
  });

  it("lists collections with a count and confirms deletion", async () => {
    mockGet([collection]);
    (axiosInstance.delete as jest.Mock).mockResolvedValue({});
    renderAt("/organizations/org-1/settings/collection");

    expect(await screen.findByRole("heading", { name: "Collections (1)" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "aws-credentials" })).toHaveAttribute(
      "href",
      "/organizations/org-1/settings/collection/edit/col-1"
    );

    fireEvent.click(screen.getByRole("button", { name: "Delete variable collection aws-credentials" }));
    expect(axiosInstance.delete).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", { name: "Delete variable collection" }));

    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/collection/col-1"));
  });
});

describe("CreateEditCollection", () => {
  beforeEach(() => jest.clearAllMocks());

  it("opens the new collection after creating it", async () => {
    mockGet([]);
    (axiosInstance.post as jest.Mock).mockResolvedValue({ data: { data: { id: "col-9" } } });
    renderAt("/organizations/org-1/settings/collection/new");

    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "datadog" } });
    fireEvent.click(screen.getByRole("button", { name: "Create variable collection" }));

    await waitFor(() =>
      expect(axiosInstance.post).toHaveBeenCalledWith(
        "organization/org-1/collection",
        { data: { type: "collection", attributes: { name: "datadog", description: "", priority: 10 } } },
        expect.anything()
      )
    );
    expect(await screen.findByRole("heading", { name: "Edit variable collection" })).toBeInTheDocument();
  });

  it("confirms before deleting a variable", async () => {
    mockGet([collection], [variable]);
    (axiosInstance.delete as jest.Mock).mockResolvedValue({});
    renderAt("/organizations/org-1/settings/collection/edit/col-1");

    expect(await screen.findByRole("heading", { name: "Variables (1)" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete variable AWS_REGION" }));
    expect(axiosInstance.delete).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete variable" }));
    await waitFor(() =>
      expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/collection/col-1/item/item-1")
    );
  });

  it("deletes the collection from the edit page after the name is typed, then returns to the list", async () => {
    mockGet([collection], [variable]);
    (axiosInstance.delete as jest.Mock).mockResolvedValue({});
    renderAt("/organizations/org-1/settings/collection/edit/col-1");

    fireEvent.click(await screen.findByRole("button", { name: "Delete this variable collection" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("its workspaces stop receiving these variables");
    const confirm = within(dialog).getByRole("button", { name: "Delete this variable collection" });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText("Type the name to confirm"), {
      target: { value: "aws-credentials" },
    });
    fireEvent.click(confirm);

    expect(await screen.findByRole("heading", { name: "Variable collections" })).toBeInTheDocument();
    expect((axiosInstance.delete as jest.Mock).mock.calls.map(([url]) => url)).toEqual([
      "organization/org-1/collection/col-1/item/item-1",
      "organization/org-1/collection/col-1",
    ]);
  });

  it("disables the collection delete without the manage collections permission", async () => {
    mockGet([collection]);
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/collection/edit/col-1"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/collection/edit/:collectionid"
            element={<CreateEditCollection mode="edit" managePermission={false} />}
          />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByRole("button", { name: "Delete this variable collection" })).toBeDisabled();
  });
});
