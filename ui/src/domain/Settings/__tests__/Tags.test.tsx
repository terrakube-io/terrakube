import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { TagsSettings } from "../Tags";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), patch: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
  isPermissionError: () => false,
}));

const renderTags = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/settings/tags"]}>
      <Routes>
        <Route path="/organizations/:orgid/settings/tags" element={<TagsSettings />} />
      </Routes>
    </MemoryRouter>
  );

describe("TagsSettings", () => {
  beforeEach(() => jest.clearAllMocks());

  it("shows an empty state with one create action", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [] } });
    renderTags();

    expect(await screen.findByText(/No tag keys yet/)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("lists tags with a count and confirms deletion", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: { data: [{ id: "tag-1", attributes: { name: "production" } }] },
    });
    (axiosInstance.delete as jest.Mock).mockResolvedValue({});
    renderTags();

    expect(await screen.findByRole("heading", { name: "Tags (1)" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete tag production" }));
    expect(axiosInstance.delete).not.toHaveBeenCalled();

    fireEvent.click(await screen.findByRole("button", { name: "Delete tag" }));
    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/tag/tag-1"));
  });

  it("creates a tag from the modal", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: { data: [{ id: "tag-1", attributes: { name: "production" } }] },
    });
    (axiosInstance.post as jest.Mock).mockResolvedValue({});
    renderTags();

    await screen.findByRole("heading", { name: "Tags (1)" });
    fireEvent.click(screen.getByRole("button", { name: /Create tag key/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Key"), { target: { value: "staging" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create tag key" }));

    await waitFor(() =>
      expect(axiosInstance.post).toHaveBeenCalledWith(
        "organization/org-1/tag",
        { data: { type: "tag", attributes: { name: "staging" } } },
        expect.anything()
      )
    );
  });
});
