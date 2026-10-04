import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Tags } from "../Tags";

const getMock = jest.fn();
const patchMock = jest.fn();
const postMock = jest.fn();
const deleteMock = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => getMock(...args),
    patch: (...args: unknown[]) => patchMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
  },
  getErrorMessage: () => "error",
}));

const clearTagFilterCacheMock = jest.fn();

jest.mock("@/modules/workspaces/workspaceService", () => ({
  __esModule: true,
  default: { clearTagFilterCache: () => clearTagFilterCacheMock() },
}));

const bindings = {
  data: {
    data: [
      { id: "wt-1", type: "workspacetag", attributes: { tagId: "tag-1", value: "prod" } },
      { id: "wt-2", type: "workspacetag", attributes: { tagId: "tag-2", value: "infra" } },
    ],
  },
};
const organizationTags = {
  data: {
    data: [
      { id: "tag-1", type: "tag", attributes: { name: "env" } },
      { id: "tag-2", type: "tag", attributes: { name: "team" } },
      { id: "tag-3", type: "tag", attributes: { name: "owner" } },
    ],
  },
};

function renderTags(manageWorkspace = true) {
  getMock.mockImplementation((url: string, config?: { params?: Record<string, string> }) => {
    if (url.endsWith("workspaceTag")) return Promise.resolve(bindings);
    const filter = config?.params?.["filter[tag]"];
    if (filter) {
      const name = JSON.parse(filter.replace("name==", ""));
      return Promise.resolve({
        data: { data: organizationTags.data.data.filter((tag) => tag.attributes.name === name) },
      });
    }
    return Promise.resolve(organizationTags);
  });
  render(<Tags organizationId="org-1" workspaceId="ws-1" manageWorkspace={manageWorkspace} />);
}

async function openEditor() {
  // The button ignores clicks while the tags are still loading
  await screen.findByTitle("env = prod");
  await userEvent.click(screen.getByRole("button", { name: /Edit tags/ }));
  return screen.findByLabelText("Value of env");
}

// After a save the button keeps its spinner for the length of the fade-out
const save = async () => userEvent.click(await screen.findByRole("button", { name: "Save" }));

describe("Tags", () => {
  beforeEach(() => {
    [getMock, patchMock, postMock, deleteMock, clearTagFilterCacheMock].forEach((mock) => mock.mockReset());
  });

  it("shows the tags as chips, with the editor behind a button", async () => {
    renderTags();

    expect(await screen.findByTitle("env = prod")).toBeInTheDocument();
    expect(screen.queryByLabelText("Value of env")).not.toBeInTheDocument();
    expect(await openEditor()).toHaveValue("prod");
  });

  it("offers no editor without the manage permission", async () => {
    renderTags(false);

    expect(await screen.findByTitle("env = prod")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Edit tags/ })).not.toBeInTheDocument();
  });

  it("sends nothing until Save", async () => {
    patchMock.mockResolvedValue({});
    renderTags();
    const input = await openEditor();

    await userEvent.clear(input);
    await userEvent.type(input, " staging ");
    await userEvent.tab();
    expect(patchMock).not.toHaveBeenCalled();

    await save();

    await waitFor(() => expect(patchMock).toHaveBeenCalledTimes(1));
    expect(patchMock.mock.calls[0][1].data.attributes.value).toBe("staging");
    expect(await screen.findByTitle("env = staging")).toBeInTheDocument();
  });

  it("drops every change on Cancel", async () => {
    renderTags();
    const input = await openEditor();

    await userEvent.clear(input);
    await userEvent.type(input, "staging");
    await userEvent.click(screen.getByRole("button", { name: "Remove team" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(patchMock).not.toHaveBeenCalled();
    expect(deleteMock).not.toHaveBeenCalled();
    expect(await openEditor()).toHaveValue("prod");
    expect(screen.getByLabelText("Value of team")).toHaveValue("infra");
  });

  it("sends removals, value changes and new tags together", async () => {
    deleteMock.mockResolvedValue({});
    patchMock.mockResolvedValue({});
    postMock.mockResolvedValue({
      data: { data: { id: "wt-3", type: "workspacetag", attributes: { tagId: "tag-3", value: "alice" } } },
    });
    renderTags();
    const input = await openEditor();

    await userEvent.clear(input);
    await userEvent.type(input, "dev");
    await userEvent.click(screen.getByRole("button", { name: "Remove team" }));
    await userEvent.click(screen.getByRole("button", { name: /Add tag/ }));
    await userEvent.type(screen.getByLabelText("New tag key"), "owner");
    await userEvent.type(screen.getByLabelText("New tag value"), "alice");
    await save();

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    expect(deleteMock.mock.calls[0][0]).toMatch(/workspaceTag\/wt-2$/);
    expect(patchMock.mock.calls[0][1].data.attributes.value).toBe("dev");
    expect(postMock.mock.calls[0][1].data.attributes).toEqual({ tagId: "tag-3", value: "alice" });
    expect(await screen.findByTitle("owner = alice")).toBeInTheDocument();
    expect(screen.queryByTitle("team = infra")).not.toBeInTheDocument();
    // A known key is taken from the loaded list, and the workspace list forgets what its tag filter matched
    expect(getMock).toHaveBeenCalledTimes(2);
    expect(clearTagFilterCacheMock).toHaveBeenCalledTimes(1);
  });

  it("looks a new key up by its quoted name before creating it", async () => {
    postMock
      .mockResolvedValueOnce({ data: { data: { id: "tag-9", type: "tag", attributes: { name: "cost center" } } } })
      .mockResolvedValueOnce({
        data: { data: { id: "wt-9", type: "workspacetag", attributes: { tagId: "tag-9", value: "42" } } },
      });
    renderTags();
    await openEditor();

    await userEvent.click(screen.getByRole("button", { name: /Add tag/ }));
    await userEvent.type(screen.getByLabelText("New tag key"), "cost center");
    await userEvent.type(screen.getByLabelText("New tag value"), "42");
    await save();

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(2));
    expect(getMock).toHaveBeenCalledWith("organization/org-1/tag", {
      params: { "filter[tag]": 'name=="cost center"' },
    });
    expect(postMock.mock.calls[0][1].data.attributes).toEqual({ name: "cost center" });
    expect(postMock.mock.calls[1][1].data.attributes).toEqual({ tagId: "tag-9", value: "42" });
    expect(await screen.findByTitle("cost center = 42")).toBeInTheDocument();
  });

  it("refuses a key listed twice", async () => {
    renderTags();
    await openEditor();

    await userEvent.click(screen.getByRole("button", { name: /Add tag/ }));
    await userEvent.type(screen.getByLabelText("New tag key"), "env");
    await save();

    expect(await screen.findByText(/"env" is listed more than once/)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it("keeps the editor open with only the failed change pending when part of a save fails", async () => {
    deleteMock.mockResolvedValue({});
    patchMock.mockRejectedValue(new Error("boom"));
    renderTags();
    const input = await openEditor();

    await userEvent.clear(input);
    await userEvent.type(input, "dev");
    await userEvent.click(screen.getByRole("button", { name: "Remove team" }));
    await save();

    expect(await screen.findByText("1 of 2 changes could not be saved. The others were saved.")).toBeInTheDocument();
    expect(screen.getByLabelText("Value of env")).toHaveValue("dev");
    expect(screen.queryByLabelText("Value of team")).not.toBeInTheDocument();

    patchMock.mockResolvedValue({});
    await save();

    await waitFor(() => expect(patchMock).toHaveBeenCalledTimes(2));
    expect(deleteMock).toHaveBeenCalledTimes(1);
  });
});
