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

  const sharedNamesUrl =
    "/organization/org-1/workspace?fields[workspace]=name&filter[workspace]=id%3Din%3D('ws-a'%2C'ws-b')";
  const names = (...ids: string[]) => ({
    data: { data: ids.map((id) => ({ id, type: "workspace", attributes: { name: `name-${id}` } })) },
  });

  it("loads the shared workspace names in one request for names only", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue(names("ws-a", "ws-b"));
    render(<WorkspaceStateShared workspace={shared()} manageWorkspace={true} />);

    expect(await screen.findByText("name-ws-a")).toBeInTheDocument();
    expect(screen.getByText("name-ws-b")).toBeInTheDocument();
    expect(axiosInstance.get).toHaveBeenCalledTimes(1);
    expect(axiosInstance.get).toHaveBeenCalledWith(sharedNamesUrl);
  });

  it("keeps a shared workspace the API no longer returns so its access can be revoked", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue(names("ws-b"));
    render(<WorkspaceStateShared workspace={shared()} manageWorkspace={true} />);

    expect(await screen.findByText("name-ws-b")).toBeInTheDocument();
    expect(screen.getByText("Unknown workspace")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove access for Unknown workspace" })).toBeInTheDocument();
  });

  it("searches by a quoted name and asks for names only", async () => {
    render(<WorkspaceStateShared workspace={workspace({ globalRemoteState: false })} manageWorkspace={true} />);

    fireEvent.change(screen.getByRole("combobox", { name: "Add a workspace by name" }), {
      target: { value: "it's prod" },
    });

    await waitFor(() =>
      expect(axiosInstance.get).toHaveBeenCalledWith(
        "/organization/org-1/workspace?fields[workspace]=name&filter[workspace]=name%3D%3D'*it%5C's%20prod*'"
      )
    );
  });

  it("asks for confirmation before revoking access", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue(names("ws-a", "ws-b"));
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
