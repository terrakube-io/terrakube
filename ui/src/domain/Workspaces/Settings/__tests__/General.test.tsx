import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { WorkspaceGeneral } from "../General";
import axiosInstance from "../../../../config/axiosConfig";

jest.mock("../../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), patch: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));
jest.mock("@/modules/projects/projectService", () => ({
  __esModule: true,
  default: { listProjects: jest.fn(() => Promise.resolve({ isError: false, data: [] })) },
}));
let mockOrgPermissions = { manageVcs: false, manageWorkspace: true };
jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions: mockOrgPermissions }),
}));

const REPO = "https://github.com/acme/infra.git";

const workspace = (source: string, relationships: Record<string, unknown> = {}) =>
  ({
    id: "ws-1",
    attributes: {
      name: "prod",
      source,
      branch: "main",
      iacType: "terraform",
      terraformVersion: "1.9.0",
      restrictApplyToConfiguredBranch: false,
    },
    relationships: {
      organization: { data: { id: "org-1" } },
      vcs: { data: { type: "vcs", id: "vcs-1" } },
      ...relationships,
    },
  }) as any;

const renderGeneral = (ws: any, onWorkspaceUpdate = jest.fn()) =>
  render(
    <MemoryRouter>
      <WorkspaceGeneral workspaceData={ws} orgTemplates={[]} manageWorkspace onWorkspaceUpdate={onWorkspaceUpdate} />
    </MemoryRouter>
  );

const changeRepoAndSave = async (url: string) => {
  fireEvent.change(await screen.findByLabelText("Repository URL"), { target: { value: url } });
  fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
};

const savedSource = () =>
  (axiosInstance.post as jest.Mock).mock.calls[0][1]["atomic:operations"][0].data.attributes.source;

beforeAll(() => {
  window._env_ = { ...window._env_, REACT_APP_TERRAKUBE_API_URL: "http://localhost/api" };
});

beforeEach(() => {
  jest.clearAllMocks();
  mockOrgPermissions = { manageVcs: false, manageWorkspace: true };
  (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
    Promise.resolve({ data: url.endsWith("index.json") ? { versions: { "1.9.0": {} } } : { data: [] } })
  );
  (axiosInstance.post as jest.Mock).mockResolvedValue({ status: 200 });
  (axiosInstance.patch as jest.Mock).mockResolvedValue({ status: 204 });
  (axiosInstance.delete as jest.Mock).mockResolvedValue({ status: 204 });
});

describe("WorkspaceGeneral repository URL", () => {
  it("saves a new repository URL without a confirmation when there is no webhook", async () => {
    renderGeneral(workspace(REPO));

    await changeRepoAndSave("git@gitlab.com:acme/infra.git");

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedSource()).toBe("git@gitlab.com:acme/infra.git");
    expect(axiosInstance.delete).not.toHaveBeenCalled();
  });

  it("trims a new repository URL before saving", async () => {
    renderGeneral(workspace(REPO));

    await changeRepoAndSave("  https://gitlab.com/acme/infra.git  ");

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedSource()).toBe("https://gitlab.com/acme/infra.git");
  });

  it("does not delete the webhook when only repository URL whitespace changes", async () => {
    renderGeneral(workspace(REPO, { webhook: { data: { type: "webhook", id: "hook-1" } } }));

    await changeRepoAndSave(`  ${REPO}  `);

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedSource()).toBe(REPO);
    expect(axiosInstance.delete).not.toHaveBeenCalled();
    expect(screen.queryByText(/Saving deletes it/)).not.toBeInTheDocument();
  });

  it.each(["request rejection", "unsuccessful response"])(
    "refreshes the deleted webhook and explains an update failure: %s",
    async (failure) => {
      const onWorkspaceUpdate = jest.fn();
      const consoleError = jest.spyOn(console, "error").mockImplementation(() => {});
      if (failure === "request rejection") {
        (axiosInstance.post as jest.Mock).mockRejectedValue(new Error("Update unavailable"));
      } else {
        (axiosInstance.post as jest.Mock).mockResolvedValue({ status: 500 });
      }
      renderGeneral(workspace(REPO, { webhook: { data: { type: "webhook", id: "hook-1" } } }), onWorkspaceUpdate);

      try {
        await changeRepoAndSave("https://gitlab.com/acme/infra.git");
        fireEvent.click(await screen.findByRole("button", { name: "Delete webhook and save" }));

        expect(await screen.findByText(/Workspace update failed after the webhook was deleted/)).toBeInTheDocument();
        expect(onWorkspaceUpdate).toHaveBeenCalledTimes(1);
        expect(onWorkspaceUpdate.mock.invocationCallOrder[0]).toBeLessThan(
          (axiosInstance.post as jest.Mock).mock.invocationCallOrder[0]
        );
      } finally {
        consoleError.mockRestore();
      }
    }
  );

  it("deletes the webhook before pointing the workspace at another repository", async () => {
    renderGeneral(workspace(REPO, { webhook: { data: { type: "webhook", id: "hook-1" } } }));

    await changeRepoAndSave("https://gitlab.com/acme/infra.git");

    expect(await screen.findByText(/Saving deletes it/)).toBeInTheDocument();
    expect(axiosInstance.post).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Delete webhook and save" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(axiosInstance.delete).toHaveBeenCalledWith(
      "organization/org-1/workspace/ws-1/webhook/hook-1",
      expect.anything()
    );
    // The API removes the remote hook using the workspace's current repository, so the delete must land first.
    expect((axiosInstance.delete as jest.Mock).mock.invocationCallOrder[0]).toBeLessThan(
      (axiosInstance.post as jest.Mock).mock.invocationCallOrder[0]
    );
    expect(savedSource()).toBe("https://gitlab.com/acme/infra.git");
  });

  it("leaves the workspace unchanged when the webhook cannot be deleted", async () => {
    (axiosInstance.delete as jest.Mock).mockRejectedValue(new Error("424"));
    renderGeneral(workspace(REPO, { webhook: { data: { type: "webhook", id: "hook-1" } } }));

    await changeRepoAndSave("https://gitlab.com/acme/infra.git");
    fireEvent.click(await screen.findByRole("button", { name: "Delete webhook and save" }));

    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalled());
    expect(axiosInstance.post).not.toHaveBeenCalled();
    expect(axiosInstance.patch).not.toHaveBeenCalled();
  });

  it("retries the update when an earlier save already deleted the webhook", async () => {
    (axiosInstance.delete as jest.Mock).mockRejectedValue({ response: { status: 404 } });
    renderGeneral(workspace(REPO, { webhook: { data: { type: "webhook", id: "hook-1" } } }));

    await changeRepoAndSave("https://gitlab.com/acme/infra.git");
    fireEvent.click(await screen.findByRole("button", { name: "Delete webhook and save" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedSource()).toBe("https://gitlab.com/acme/infra.git");
  });

  it("also deletes the webhook when only the VCS provider changes", async () => {
    (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
      Promise.resolve({
        data: url.endsWith("index.json")
          ? { versions: { "1.9.0": {} } }
          : url.endsWith("/vcs")
            ? { data: [{ id: "vcs-2", attributes: { name: "GitLab", vcsType: "GITLAB" } }] }
            : { data: [] },
      })
    );
    renderGeneral(workspace(REPO, { webhook: { data: { type: "webhook", id: "hook-1" } } }));

    fireEvent.mouseDown(await screen.findByLabelText("VCS provider"));
    fireEvent.click(await screen.findByText("GitLab"));
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete webhook and save" }));

    await waitFor(() =>
      expect(axiosInstance.patch).toHaveBeenCalledWith(
        "/organization/org-1/workspace/ws-1/relationships/vcs",
        { data: { type: "vcs", id: "vcs-2" } },
        expect.anything()
      )
    );
    expect(axiosInstance.delete).toHaveBeenCalledWith(
      "organization/org-1/workspace/ws-1/webhook/hook-1",
      expect.anything()
    );
  });

  it("does not accept the CLI-driven marker as a repository", async () => {
    renderGeneral(workspace(REPO));

    await changeRepoAndSave("empty");

    expect(await screen.findByText("Enter a Git URL.")).toBeInTheDocument();
    expect(axiosInstance.post).not.toHaveBeenCalled();
  });

  it("hides the field for CLI-driven workspaces and does not send a source", async () => {
    renderGeneral(workspace("empty"));

    expect(await screen.findByLabelText("VCS provider")).toBeInTheDocument();
    expect(screen.queryByLabelText("Repository URL")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedSource()).toBeUndefined();
  });
});

describe("WorkspaceGeneral branch restriction", () => {
  const savedAttributes = () =>
    (axiosInstance.post as jest.Mock).mock.calls[0][1]["atomic:operations"][0].data.attributes;

  it("saves the setting with the workspace for an organization manager", async () => {
    renderGeneral(workspace(REPO));

    fireEvent.click(
      await screen.findByRole("checkbox", { name: /Only run apply and destroy from the default branch/ })
    );
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedAttributes().restrictApplyToConfiguredBranch).toBe(true);
  });

  it("shows the saved value", async () => {
    const restricted = workspace(REPO);
    restricted.attributes.restrictApplyToConfiguredBranch = true;
    renderGeneral(restricted);

    expect(
      await screen.findByRole("checkbox", { name: /Only run apply and destroy from the default branch/ })
    ).toBeChecked();
  });

  it("is read-only and not sent for a manager limited to the workspace", async () => {
    mockOrgPermissions = { manageVcs: false, manageWorkspace: false };
    renderGeneral(workspace(REPO));

    expect(
      await screen.findByRole("checkbox", { name: /Only run apply and destroy from the default branch/ })
    ).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedAttributes()).not.toHaveProperty("restrictApplyToConfiguredBranch");
  });

  it("is disabled with an explanation on a CLI-driven workspace, which has no branch to restrict", async () => {
    renderGeneral(workspace("empty", { vcs: undefined }));

    expect(
      await screen.findByRole("checkbox", { name: /Only run apply and destroy from the default branch/ })
    ).toBeDisabled();
    expect(screen.getByText(/This workspace is CLI-driven/)).toBeInTheDocument();
  });

  it("hides the setting and does not send it to a Terrakube that does not report it", async () => {
    const older = workspace(REPO);
    delete older.attributes.restrictApplyToConfiguredBranch;
    renderGeneral(older);

    await screen.findByLabelText("Repository URL");
    expect(
      screen.queryByRole("checkbox", { name: /Only run apply and destroy from the default branch/ })
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect(savedAttributes()).not.toHaveProperty("restrictApplyToConfiguredBranch");
  });
});
