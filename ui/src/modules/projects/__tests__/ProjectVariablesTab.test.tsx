import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import projectService from "../projectService";
import ProjectVariablesTab from "../ProjectVariablesTab";

jest.mock("../projectService", () => ({
  __esModule: true,
  default: {
    listCollectionsForProject: jest.fn(),
    attachCollectionToProject: jest.fn(),
    detachCollectionFromProject: jest.fn(),
  },
}));

const list = projectService.listCollectionsForProject as jest.Mock;

const collections = [
  { id: "c1", name: "Shared tags", priority: 10, variableCount: 3, referenceId: "r1" },
  { id: "c2", name: "Unattached", priority: 5, variableCount: 1 },
];

describe("ProjectVariablesTab", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    list.mockResolvedValue({ isError: false, data: collections });
  });

  it("lists only the collections attached to the project", async () => {
    render(
      <MemoryRouter>
        <ProjectVariablesTab orgid="org" projectId="p1" canManage />
      </MemoryRouter>
    );

    expect(await screen.findByText("Shared tags")).toBeInTheDocument();
    expect(screen.queryByText("Unattached")).not.toBeInTheDocument();
  });

  it("links to creating a collection when the person can manage them", async () => {
    render(
      <MemoryRouter>
        <ProjectVariablesTab orgid="org" projectId="p1" canManage />
      </MemoryRouter>
    );

    expect(await screen.findByRole("link", { name: "Create a collection" })).toHaveAttribute(
      "href",
      "/organizations/org/settings/collection/new"
    );
  });

  it("attaches a collection that is not yet attached", async () => {
    (projectService.attachCollectionToProject as jest.Mock).mockResolvedValue(undefined);
    render(
      <MemoryRouter>
        <ProjectVariablesTab orgid="org" projectId="p1" canManage />
      </MemoryRouter>
    );
    await screen.findByText("Shared tags");

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Collection to attach" }));
    fireEvent.click(await screen.findByText("Unattached"));
    fireEvent.click(screen.getByRole("button", { name: "Attach" }));

    await waitFor(() => expect(projectService.attachCollectionToProject).toHaveBeenCalledWith("org", "p1", "c2"));
  });

  it("sends one attach however many times the button is pressed", async () => {
    let finish: () => void = () => undefined;
    (projectService.attachCollectionToProject as jest.Mock).mockReturnValue(
      new Promise<void>((resolve) => {
        finish = resolve;
      })
    );
    render(
      <MemoryRouter>
        <ProjectVariablesTab orgid="org" projectId="p1" canManage />
      </MemoryRouter>
    );
    await screen.findByText("Shared tags");

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Collection to attach" }));
    fireEvent.click(await screen.findByText("Unattached"));
    const attach = screen.getByRole("button", { name: "Attach" });
    fireEvent.click(attach);
    fireEvent.click(attach);
    finish();

    await waitFor(() => expect(projectService.attachCollectionToProject).toHaveBeenCalledTimes(1));
  });

  it("shows the attach control disabled, and no detach, without permission", async () => {
    render(
      <MemoryRouter>
        <ProjectVariablesTab orgid="org" projectId="p1" canManage={false} />
      </MemoryRouter>
    );
    await screen.findByText("Shared tags");

    expect(screen.getByRole("combobox", { name: "Collection to attach" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Attach" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "View collections" })).toHaveAttribute(
      "href",
      "/organizations/org/settings/collection"
    );
    expect(screen.queryByText("Detach")).not.toBeInTheDocument();
  });

  it("shows an error when the collections cannot be loaded", async () => {
    list.mockResolvedValue({ isError: true, error: { message: "boom" }, data: [] });
    render(
      <MemoryRouter>
        <ProjectVariablesTab orgid="org" projectId="p1" canManage />
      </MemoryRouter>
    );

    expect(await screen.findByText("boom")).toBeInTheDocument();
  });
});
