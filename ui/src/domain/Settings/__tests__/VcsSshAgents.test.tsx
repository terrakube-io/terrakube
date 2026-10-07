import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { message } from "antd";
import axiosInstance from "../../../config/axiosConfig";
import { SSHKeysSettings } from "../SSHKeys";
import { AgentSettings } from "../Agents";
import { VCSSettings } from "../VCS";
import { AddVCS } from "../AddVCS";
import { FederatedCredentials } from "../FederatedCredentials";
import { EditFederatedCredential } from "../EditFederatedCredential";
import { getConnectUrl } from "../vcsProviders";
import { VcsType } from "../../types";

jest.mock("uuid", () => ({ v1: () => "uuid-1" }));

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: jest.fn().mockResolvedValue({ data: { data: [] } }),
    post: jest.fn().mockResolvedValue({ status: 201, data: { data: { id: "new-1", attributes: {} } } }),
    patch: jest.fn().mockResolvedValue({}),
    delete: jest.fn().mockResolvedValue({}),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
  isPermissionError: () => false,
}));

const renderAt = (path: string, route: string, element: React.ReactElement) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={route} element={element} />
      </Routes>
    </MemoryRouter>
  );

const get = axiosInstance.get as jest.Mock;

beforeEach(() => {
  jest.clearAllMocks();
  get.mockResolvedValue({ data: { data: [] } });
});

describe("SSH keys", () => {
  const renderPage = () =>
    renderAt("/organizations/org-1/settings/ssh", "/organizations/:orgid/settings/ssh", <SSHKeysSettings />);

  it("shows an empty state with one action instead of an empty list", async () => {
    renderPage();
    expect(await screen.findByText(/No SSH keys yet/)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Add an SSH key/ })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: /Add an SSH key/ })[0]).toHaveClass("ant-btn-primary");
    expect(screen.getAllByRole("button", { name: /Add an SSH key/ })[1]).not.toHaveClass("ant-btn-primary");
  });

  it("deletes a key only after its name is typed", async () => {
    get.mockImplementation((url: string) =>
      Promise.resolve({
        data: {
          data: url.includes("filter[")
            ? []
            : [{ id: "k1", attributes: { name: "deploy", description: "Modules", sshType: "rsa" } }],
        },
      })
    );
    renderPage();

    expect(await screen.findByRole("heading", { name: "SSH keys (1)" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete SSH key deploy" }));

    const dialog = await screen.findByRole("dialog");
    const confirm = within(dialog).getByRole("button", { name: "Delete SSH key" });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText("Type the name to confirm"), { target: { value: "deploy" } });
    fireEvent.click(confirm);

    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/ssh/k1"));
  });
});

describe("Agents", () => {
  it("confirms a delete with the consequence and labels the icon action", async () => {
    get.mockImplementation((url: string) =>
      Promise.resolve({
        data: {
          data: url.includes("filter[")
            ? []
            : [{ id: "a1", attributes: { name: "pool-a", description: "Runners", url: "http://agent:8090" } }],
        },
      })
    );
    renderAt("/organizations/org-1/settings/agents", "/organizations/:orgid/settings/agents", <AgentSettings />);

    expect(await screen.findByText("http://agent:8090")).toHaveClass("resource-mono");
    fireEvent.click(screen.getByRole("button", { name: "Delete agent pool pool-a" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("can only be deleted when no workspace is assigned to it");
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete agent pool" }));

    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/agent/a1"));
  });
});

describe("delete refusals", () => {
  const confirmDelete = async (button: string, okText: string, name?: string) => {
    fireEvent.click(await screen.findByRole("button", { name: button }));
    const dialog = await screen.findByRole("dialog");
    if (name) fireEvent.change(within(dialog).getByLabelText("Type the name to confirm"), { target: { value: name } });
    fireEvent.click(within(dialog).getByRole("button", { name: okText }));
  };

  it("refuses to delete an agent pool that workspaces still use, with the count", async () => {
    const errorSpy = jest.spyOn(message, "error");
    get.mockImplementation((url: string) =>
      Promise.resolve({
        data: {
          data: url.includes("filter[workspace]=agent.id==a1")
            ? [{ id: "w1" }]
            : [{ id: "a1", attributes: { name: "pool-a" } }],
        },
      })
    );
    renderAt("/organizations/org-1/settings/agents", "/organizations/:orgid/settings/agents", <AgentSettings />);

    await confirmDelete("Delete agent pool pool-a", "Delete agent pool");

    await waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith("pool-a is used by 1 workspace. Move them to another agent pool first.")
    );
    expect(axiosInstance.delete).not.toHaveBeenCalled();
  });

  it("refuses to delete an SSH key that workspaces or modules still use, with the counts", async () => {
    const errorSpy = jest.spyOn(message, "error");
    get.mockImplementation((url: string) =>
      Promise.resolve({
        data: {
          data: url.includes("filter[workspace]=ssh.id==k1")
            ? [{ id: "w1" }, { id: "w2" }]
            : url.includes("filter[module]=ssh.id==k1")
              ? [{ id: "m1" }]
              : [{ id: "k1", attributes: { name: "deploy" } }],
        },
      })
    );
    renderAt("/organizations/org-1/settings/ssh", "/organizations/:orgid/settings/ssh", <SSHKeysSettings />);

    await confirmDelete("Delete SSH key deploy", "Delete SSH key", "deploy");

    await waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith(
        "deploy is used by 2 workspaces and 1 module. Move them to another SSH key first."
      )
    );
    expect(axiosInstance.delete).not.toHaveBeenCalled();
  });
});

describe("VCS providers", () => {
  const provider = (attributes: Record<string, unknown>) => ({
    id: "v1",
    attributes: {
      name: "corp-github",
      vcsType: "GITHUB",
      connectionType: "OAUTH",
      status: "PENDING",
      clientId: "abc123",
      callback: "cb-1",
      ...attributes,
    },
  });
  const renderList = () =>
    renderAt("/organizations/org-1/settings/vcs", "/organizations/:orgid/settings/vcs", <VCSSettings />);

  it("offers to connect pending OAuth providers but not managed identities", async () => {
    get.mockResolvedValueOnce({
      data: {
        data: [provider({}), { ...provider({ name: "azure-mi", vcsType: "AZURE_SP_MI", callback: "cb-2" }), id: "v2" }],
      },
    });
    renderList();

    expect(await screen.findByRole("heading", { name: "VCS providers (2)" })).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /Connect to/ })).toHaveLength(1);
    expect(screen.getByText("Not connected")).toBeInTheDocument();
    expect(screen.getByText("https://terrakube-api.test/callback/v1/vcs/cb-1")).toHaveClass("resource-mono");
  });

  it("refuses to delete a provider that workspaces still use, with the count", async () => {
    const errorSpy = jest.spyOn(message, "error");
    get.mockResolvedValueOnce({ data: { data: [provider({ status: "COMPLETED" })] } });
    get.mockResolvedValueOnce({ data: { data: provider({}), included: [{ id: "w1" }, { id: "w2" }] } });
    renderList();

    fireEvent.click(await screen.findByRole("button", { name: "Delete VCS provider corp-github" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Type the name to confirm"), {
      target: { value: "corp-github" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete VCS provider" }));

    await waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith(
        "corp-github is used by 2 workspaces. Move them to another VCS provider first."
      )
    );
    expect(axiosInstance.delete).not.toHaveBeenCalled();
  });
});

describe("VCS provider delete", () => {
  it("refuses to delete a provider that modules or policy sets still use", async () => {
    const errorSpy = jest.spyOn(message, "error");
    get.mockImplementation((url: string) =>
      Promise.resolve({
        data: {
          data: url.includes("filter[module]=vcs.id==v1")
            ? [{ id: "m1" }, { id: "m2" }]
            : url.includes("filter[policy_set]=vcs.id==v1")
              ? [{ id: "p1" }]
              : url.includes("include=workspace")
                ? { id: "v1" }
                : [{ id: "v1", attributes: { name: "corp-github", vcsType: "GITHUB", connectionType: "STANDALONE" } }],
        },
      })
    );
    renderAt("/organizations/org-1/settings/vcs", "/organizations/:orgid/settings/vcs", <VCSSettings />);

    fireEvent.click(await screen.findByRole("button", { name: "Delete VCS provider corp-github" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Type the name to confirm"), { target: { value: "corp-github" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete VCS provider" }));

    await waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith(
        "corp-github is used by 2 modules and 1 policy set. Move them to another VCS provider first."
      )
    );
    expect(axiosInstance.delete).not.toHaveBeenCalled();
  });

  it("explains a conflict from a shared repository webhook the usage check can't see", async () => {
    const errorSpy = jest.spyOn(message, "error");
    get.mockImplementation((url: string) =>
      Promise.resolve({
        data: url.includes("include=workspace")
          ? { data: { id: "v1" } }
          : url.includes("filter[")
            ? { data: [] }
            : {
                data: [
                  { id: "v1", attributes: { name: "corp-github", vcsType: "GITHUB", connectionType: "STANDALONE" } },
                ],
              },
      })
    );
    (axiosInstance.delete as jest.Mock).mockRejectedValueOnce({ response: { status: 409 } });
    renderAt("/organizations/org-1/settings/vcs", "/organizations/:orgid/settings/vcs", <VCSSettings />);

    fireEvent.click(await screen.findByRole("button", { name: "Delete VCS provider corp-github" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent(
      "no workspace, module, policy set or shared repository webhook uses this provider"
    );
    fireEvent.change(within(dialog).getByLabelText("Type the name to confirm"), { target: { value: "corp-github" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete VCS provider" }));

    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/vcs/v1"));
    await waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith(
        expect.stringMatching(/^Could not delete corp-github: it is still in use\. A shared repository webhook/)
      )
    );
  });
});

describe("Add VCS provider", () => {
  const renderAdd = () =>
    renderAt(
      "/organizations/org-1/settings/vcs/new",
      "/organizations/:orgid/settings/vcs/new",
      <AddVCS setMode={jest.fn()} loadVCS={jest.fn()} />
    );

  it("picks a provider from tiles, then its edition, then shows the setup form", async () => {
    renderAdd();

    expect(screen.getAllByRole("radio")).toHaveLength(4 + 4);
    fireEvent.click(screen.getByRole("radio", { name: "GitLab" }));
    fireEvent.click(screen.getByRole("radio", { name: /GitLab Community Edition/ }));
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(
      await screen.findByRole("heading", { name: "Register Terrakube on GitLab Community Edition" })
    ).toBeVisible();
    expect((screen.getByLabelText("Redirect URI") as HTMLInputElement).value).toBe(
      "https://terrakube-api.test/callback/v1/vcs/uuid-1"
    );
    expect(screen.getByRole("button", { name: "Copy Redirect URI" })).toBeInTheDocument();
    // Secrets are typed into a password field.
    expect(screen.getByLabelText("Secret")).toHaveAttribute("type", "password");
    expect(screen.getByRole("button", { name: "Add and connect" })).toHaveClass("ant-btn-primary");
  });

  it("creates a GitHub App provider without the OAuth redirect", async () => {
    renderAdd();
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));

    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "gh-app" } });
    fireEvent.change(screen.getByLabelText("App ID"), { target: { value: "970081" } });
    fireEvent.change(screen.getByLabelText("Private key (PKCS#8)"), {
      target: { value: "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add VCS provider" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    const [url, body] = (axiosInstance.post as jest.Mock).mock.calls[0];
    expect(url).toBe("organization/org-1/vcs");
    expect(body.data.attributes).toMatchObject({
      name: "gh-app",
      vcsType: "GITHUB",
      connectionType: "STANDALONE",
      clientId: "970081",
      endpoint: "https://github.com",
      apiUrl: "https://api.github.com",
    });
  });

  it("sends self-managed GitLab editions to GitLab's OAuth page", () => {
    expect(getConnectUrl(VcsType.GITLAB, "id", "cb", "https://git.example.com")).toMatch(
      /^https:\/\/git\.example\.com\/oauth\/authorize\?client_id=id/
    );
  });
});

describe("Federated credentials", () => {
  it("lists issuer and audience in monospace and confirms deletes by name", async () => {
    get.mockImplementation((url: string) =>
      Promise.resolve({
        data: {
          data: url.endsWith("/claims")
            ? []
            : [{ id: "f1", attributes: { name: "CI_TEAM", issuerUrl: "https://issuer.test", audience: "tk" } }],
        },
      })
    );
    renderAt(
      "/organizations/org-1/settings/federated-credentials",
      "/organizations/:orgid/settings/federated-credentials",
      <FederatedCredentials />
    );

    expect(await screen.findByText("https://issuer.test")).toHaveClass("resource-mono");
    expect(await screen.findByText("No claim conditions")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete federated credential CI_TEAM" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("button", { name: "Delete federated credential" })).toBeDisabled();
  });

  it("adds a claim condition with Enter without submitting, then saves it", async () => {
    const setMode = jest.fn();
    render(<EditFederatedCredential mode="create" setMode={setMode} loadFederated={jest.fn()} />);

    fireEvent.change(screen.getByLabelText("Team name"), { target: { value: "CI_TEAM" } });
    fireEvent.change(screen.getByLabelText("Issuer URL"), { target: { value: "https://issuer.test" } });
    fireEvent.change(screen.getByLabelText("Audience"), { target: { value: "tk" } });
    fireEvent.change(screen.getByLabelText("Claim key"), { target: { value: "repository_owner" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "acme" } });
    fireEvent.keyDown(screen.getByLabelText("Value"), { key: "Enter", code: "Enter", keyCode: 13 });

    expect(await within(screen.getByRole("table")).findByText("repository_owner")).toBeInTheDocument();
    expect(axiosInstance.post).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Add federated credential" }));
    await waitFor(() => expect(setMode).toHaveBeenCalledWith("list"));
    expect((axiosInstance.post as jest.Mock).mock.calls.map((call) => call[0])).toEqual([
      "federated",
      "federated/new-1/claims",
    ]);
  });
});
