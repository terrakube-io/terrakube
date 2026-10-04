import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { message } from "antd";
import { WorkspaceSSHKey } from "../SSHKey";
import { WorkspaceLocking } from "../Locking";
import { WorkspaceStateShared } from "../StateShared";
import axiosInstance from "../../../../config/axiosConfig";

jest.mock("../../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: jest.fn().mockResolvedValue({ data: { data: [] } }),
    post: jest.fn(),
    patch: jest.fn(),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

const workspace = (attributes: Record<string, unknown> = {}) =>
  ({
    id: "ws-1",
    attributes: { name: "prod", locked: false, ...attributes },
    relationships: { organization: { data: { id: "org-1" } } },
  }) as any;

beforeEach(() => jest.clearAllMocks());

describe("WorkspaceSSHKey", () => {
  it("lists the organization keys as options and links to the organization SSH keys page", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValueOnce({
      data: { data: [{ id: "key-1", attributes: { name: "deploy-key" } }] },
    });
    render(
      <MemoryRouter>
        <WorkspaceSSHKey workspace={workspace()} manageWorkspace={true} />
      </MemoryRouter>
    );

    expect(screen.getByRole("link", { name: "Manage organization SSH keys" })).toHaveAttribute(
      "href",
      "/organizations/org-1/settings/ssh"
    );

    fireEvent.mouseDown(screen.getByRole("combobox"));
    expect(await screen.findByText("deploy-key")).toBeInTheDocument();
    // One dropdown, not a Select nested per option.
    expect(screen.getAllByRole("combobox")).toHaveLength(1);
  });

  it("shows the server error when saving fails", async () => {
    const errorSpy = jest.spyOn(message, "error");
    (axiosInstance.post as jest.Mock).mockRejectedValueOnce(new Error("key not found"));
    render(
      <MemoryRouter>
        <WorkspaceSSHKey workspace={workspace()} manageWorkspace={true} />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole("button", { name: "Update SSH key" }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith("Could not update the SSH key: key not found"));
  });
});

describe("WorkspaceLocking", () => {
  it("locks with the given reason from a primary button", async () => {
    (axiosInstance.patch as jest.Mock).mockResolvedValueOnce({});
    const onUpdate = jest.fn();
    render(<WorkspaceLocking workspace={workspace()} manageWorkspace={true} onWorkspaceUpdate={onUpdate} />);

    fireEvent.change(screen.getByLabelText("Lock reason"), { target: { value: "maintenance" } });
    const lock = screen.getByRole("button", { name: "Lock workspace" });
    expect(lock).toHaveClass("ant-btn-primary");
    fireEvent.click(lock);

    await waitFor(() => expect(onUpdate).toHaveBeenCalled());
    expect((axiosInstance.patch as jest.Mock).mock.calls[0][1].data.attributes).toEqual({
      locked: true,
      lockDescription: "maintenance",
    });
  });

  it("shows the lock as a warning and confirms unlock truthfully", async () => {
    (axiosInstance.patch as jest.Mock).mockResolvedValueOnce({});
    const onUpdate = jest.fn();
    render(
      <WorkspaceLocking
        workspace={workspace({ locked: true, lockDescription: "maintenance" })}
        manageWorkspace={true}
        onWorkspaceUpdate={onUpdate}
      />
    );

    expect(screen.getByText(/This workspace is locked/).closest(".ant-alert")).toHaveClass("ant-alert-warning");
    expect(screen.getByText("Reason: maintenance")).toBeInTheDocument();
    const unlock = screen.getByRole("button", { name: "Unlock workspace" });
    expect(unlock).not.toHaveClass("ant-btn-primary");
    fireEvent.click(unlock);

    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("You can lock the workspace again at any time.");
    expect(dialog).not.toHaveTextContent("cannot be undone");
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlock workspace" }));

    await waitFor(() => expect(onUpdate).toHaveBeenCalled());
    expect((axiosInstance.patch as jest.Mock).mock.calls[0][1].data.attributes).toEqual({
      locked: false,
      lockDescription: "",
    });
  });
});

describe("WorkspaceStateShared", () => {
  const shared = () => workspace({ globalRemoteState: false, sharedIds: "ws-a,ws-b" });

  it("loads the shared workspace names in parallel", async () => {
    const resolvers: Array<() => void> = [];
    (axiosInstance.get as jest.Mock).mockImplementation(
      (url: string) =>
        new Promise((resolve) =>
          resolvers.push(() => resolve({ data: { data: { attributes: { name: `name-${url.split("/").pop()}` } } } }))
        )
    );
    render(<WorkspaceStateShared workspace={shared()} manageWorkspace={true} />);

    // Both requests are in flight before either resolves.
    expect(axiosInstance.get).toHaveBeenCalledTimes(2);
    resolvers.forEach((resolve) => resolve());
    expect(await screen.findByText("name-ws-a")).toBeInTheDocument();
    expect(screen.getByText("name-ws-b")).toBeInTheDocument();
  });

  it("asks for confirmation before revoking access", async () => {
    (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
      Promise.resolve({ data: { data: { attributes: { name: `name-${url.split("/").pop()}` } } } })
    );
    (axiosInstance.post as jest.Mock).mockResolvedValueOnce({ status: 200 });
    render(<WorkspaceStateShared workspace={shared()} manageWorkspace={true} />);

    fireEvent.click(await screen.findByRole("button", { name: "Remove access for name-ws-a" }));
    expect(axiosInstance.post).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove access" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    const body = (axiosInstance.post as jest.Mock).mock.calls[0][1];
    expect(body["atomic:operations"][0].data.attributes).toEqual({ sharedIds: "ws-b" });
  });
});
