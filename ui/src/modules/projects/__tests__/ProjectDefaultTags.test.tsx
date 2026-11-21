import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import projectService from "../projectService";
import ProjectDefaultTags from "../ProjectDefaultTags";

jest.mock("../projectService", () => ({
  __esModule: true,
  default: {
    listProjectDefaultTags: jest.fn(),
    listOrganizationTags: jest.fn(),
    addProjectDefaultTag: jest.fn(),
    removeProjectDefaultTag: jest.fn(),
    applyProjectDefaultTags: jest.fn(),
  },
}));

const service = projectService as jest.Mocked<typeof projectService>;

describe("ProjectDefaultTags", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    service.listProjectDefaultTags.mockResolvedValue([{ id: "pt1", tagId: "t1", value: "prod" }]);
    service.listOrganizationTags.mockResolvedValue([
      { id: "t1", name: "env" },
      { id: "t2", name: "team" },
    ]);
  });

  it("shows the default tags by name and value", async () => {
    render(<ProjectDefaultTags orgid="org" projectId="p1" canManage />);

    expect(await screen.findByText("env: prod")).toBeInTheDocument();
  });

  it("adds a tag that is not already a default", async () => {
    service.addProjectDefaultTag.mockResolvedValue(undefined);
    render(<ProjectDefaultTags orgid="org" projectId="p1" canManage />);
    await screen.findByText("env: prod");

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Tag to add" }));
    expect(screen.queryByTitle("env")).not.toBeInTheDocument();
    fireEvent.click(await screen.findByTitle("team"));
    fireEvent.change(screen.getByLabelText("Tag value"), { target: { value: "payments" } });
    fireEvent.click(screen.getByRole("button", { name: "Add tag" }));

    await waitFor(() => expect(service.addProjectDefaultTag).toHaveBeenCalledWith("org", "p1", "t2", "payments"));
  });

  it("is read only without permission", async () => {
    render(<ProjectDefaultTags orgid="org" projectId="p1" canManage={false} />);
    await screen.findByText("env: prod");

    expect(screen.queryByRole("button", { name: "Add tag" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Close")).not.toBeInTheDocument();
  });

  it("shows the impact of applying to existing workspaces before changing anything", async () => {
    service.applyProjectDefaultTags.mockResolvedValue({ workspaces: 10, workspacesChanged: 4, tagsAdded: 4 });
    render(<ProjectDefaultTags orgid="org" projectId="p1" canManage />);
    await screen.findByText("env: prod");

    fireEvent.click(screen.getByRole("button", { name: "Apply to existing workspaces" }));

    expect(await screen.findByText(/4 tags will be added to 4 of 10 workspaces/)).toBeInTheDocument();
    expect(service.applyProjectDefaultTags).toHaveBeenCalledTimes(1);
    expect(service.applyProjectDefaultTags).toHaveBeenCalledWith("org", "p1", true);
  });

  it("applies after confirmation", async () => {
    service.applyProjectDefaultTags
      .mockResolvedValueOnce({ workspaces: 2, workspacesChanged: 1, tagsAdded: 1 })
      .mockResolvedValueOnce({ workspaces: 2, workspacesChanged: 1, tagsAdded: 1 });
    render(<ProjectDefaultTags orgid="org" projectId="p1" canManage />);
    await screen.findByText("env: prod");

    fireEvent.click(screen.getByRole("button", { name: "Apply to existing workspaces" }));
    fireEvent.click(await screen.findByRole("button", { name: "Apply" }));

    await waitFor(() => expect(service.applyProjectDefaultTags).toHaveBeenLastCalledWith("org", "p1", false));
  });
});
