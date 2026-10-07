import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { WorkspaceWebhook } from "../Webhook";
import axiosInstance from "../../../../config/axiosConfig";
import { VcsType } from "../../../types";

jest.mock("../../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), patch: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));
jest.mock("../../Workspaces", () => ({ atomicHeader: {} }));
let uuidCount = 0;
jest.mock("uuid", () => ({ v7: () => `uuid-${++uuidCount}` }));

const workspace = (relationships: Record<string, unknown> = {}) =>
  ({
    id: "ws-1",
    attributes: { name: "prod" },
    relationships: { organization: { data: { id: "org-1" } }, ...relationships },
  }) as any;

const withVcs = workspace({
  vcs: { data: { type: "vcs", id: "vcs-1" } },
  webhook: { data: { type: "webhook", id: "hook-1" } },
});

const templates = [{ id: "tpl-1", type: "template", attributes: { name: "Plan and apply" } }] as any;

const renderWebhook = (ws = withVcs) =>
  render(
    <MemoryRouter>
      <WorkspaceWebhook workspace={ws} vcsProvider={VcsType.GITHUB} orgTemplates={templates} manageWorkspace />
    </MemoryRouter>
  );

beforeEach(() => {
  jest.clearAllMocks();
  (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
    Promise.resolve(
      url.endsWith("/events")
        ? {
            data: {
              data: [
                {
                  id: "ev-1",
                  attributes: {
                    priority: 1,
                    event: "PUSH",
                    branch: "main",
                    path: "terraform/*",
                    pathType: "PATTERN",
                    templateId: "tpl-1",
                  },
                },
              ],
            },
          }
        : { data: { data: { attributes: { remoteHookId: "4242", migratedV2: false } } } }
    )
  );
  (axiosInstance.post as jest.Mock).mockResolvedValue({ status: 200 });
  (axiosInstance.delete as jest.Mock).mockResolvedValue({ status: 204 });
});

describe("WorkspaceWebhook", () => {
  it("explains that webhooks need a VCS connection instead of offering the switch", () => {
    renderWebhook(workspace());

    expect(screen.getByText(/Webhooks need a VCS connection/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Choose a VCS provider in General settings" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ws-1/settings/general"
    );
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(axiosInstance.get).not.toHaveBeenCalled();
  });

  it("summarizes each trigger in one row and labels every control", async () => {
    renderWebhook();

    expect(screen.getByRole("switch", { name: "Enable webhook" })).toBeChecked();
    expect(await screen.findByText("Triggers (1)")).toBeInTheDocument();
    expect(screen.getByLabelText("Webhook ID")).toHaveValue("hook-1");
    expect(screen.getByLabelText("Repository webhook ID")).toHaveValue("4242");
    const row = screen.getByRole("listitem");
    expect(row).toHaveTextContent("Push · main · terraform/* → Plan and apply");

    const edit = within(row).getByRole("button", { name: "Edit trigger 1" });
    expect(edit).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(edit);
    expect(edit).toHaveAttribute("aria-expanded", "true");

    for (const label of ["Event", "Branches or releases", "Paths", "Template", "Priority"]) {
      expect(screen.getByLabelText(label)).toBeVisible();
    }
    expect(screen.getByRole("radio", { name: /^Pattern/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /^Regex/ })).toBeInTheDocument();
  });

  it("confirms a delete and sends it only when the webhook is saved", async () => {
    renderWebhook();
    await screen.findByText("Triggers (1)");

    fireEvent.click(screen.getByRole("button", { name: "Delete trigger 1" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Delete trigger")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(screen.queryByRole("listitem")).not.toBeInTheDocument());
    expect(axiosInstance.delete).not.toHaveBeenCalled();

    // An empty list is not saveable: the error is shown inline.
    fireEvent.click(screen.getByRole("button", { name: "Save webhook" }));
    expect(await screen.findByText("Add at least one trigger.")).toBeInTheDocument();
    expect(axiosInstance.delete).not.toHaveBeenCalled();
  });

  it("deletes removed triggers and saves the rest in one atomic request", async () => {
    (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith("/events")
          ? {
              data: {
                data: ["ev-1", "ev-2"].map((id, i) => ({
                  id,
                  attributes: {
                    priority: i + 1,
                    event: "PUSH",
                    branch: `b${i}`,
                    path: "x/*",
                    pathType: "PATTERN",
                    templateId: "tpl-1",
                  },
                })),
              },
            }
          : { data: { data: { attributes: { remoteHookId: "4242" } } } }
      )
    );
    renderWebhook();
    await screen.findByText("Triggers (2)");

    fireEvent.click(screen.getByRole("button", { name: "Delete trigger 1" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Save webhook" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalledTimes(1));
    expect(axiosInstance.delete).not.toHaveBeenCalled();
    const ops = (axiosInstance.post as jest.Mock).mock.calls[0][1]["atomic:operations"];
    expect(ops[0]).toEqual({ op: "remove", href: "/organization/org-1/workspace/ws-1/webhook/hook-1/events/ev-1" });
    expect(ops[1].relationships.events.data).toEqual([{ type: "webhook_event", id: "ev-2" }]);
    expect(ops.slice(2).map((op: any) => [op.op, op.data.id])).toEqual([["update", "ev-2"]]);
  });

  it("keeps a deletion pending when the save fails, so the next save sends it again", async () => {
    (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith("/events")
          ? {
              data: {
                data: ["ev-1", "ev-2"].map((id, i) => ({
                  id,
                  attributes: {
                    priority: i + 1,
                    event: "PUSH",
                    branch: `b${i}`,
                    path: "x/*",
                    pathType: "PATTERN",
                    templateId: "tpl-1",
                  },
                })),
              },
            }
          : { data: { data: { attributes: { remoteHookId: "4242" } } } }
      )
    );
    (axiosInstance.post as jest.Mock).mockRejectedValueOnce(new Error("Invalid value"));
    renderWebhook();
    await screen.findByText("Triggers (2)");

    fireEvent.click(screen.getByRole("button", { name: "Delete trigger 1" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Save webhook" }));
    expect(await screen.findByText("Couldn't save the webhook: Invalid value")).toBeInTheDocument();
    expect(axiosInstance.delete).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Save webhook" }));
    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalledTimes(2));
    const ops = (axiosInstance.post as jest.Mock).mock.calls[1][1]["atomic:operations"];
    expect(ops[0]).toEqual({ op: "remove", href: "/organization/org-1/workspace/ws-1/webhook/hook-1/events/ev-1" });
  });

  it("shows required-field errors inline on a new trigger", async () => {
    renderWebhook(workspace({ vcs: { data: { type: "vcs", id: "vcs-1" } } }));

    const toggle = screen.getByRole("switch", { name: "Enable webhook" });
    expect(toggle).not.toBeChecked();
    fireEvent.click(toggle);
    fireEvent.click(screen.getByRole("button", { name: "Save webhook" }));

    expect(await screen.findByText("Choose an event.")).toBeInTheDocument();
    expect(screen.getByText("Enter at least one path.")).toBeInTheDocument();
    expect(screen.getByText("Choose a template.")).toBeInTheDocument();
    expect(axiosInstance.post).not.toHaveBeenCalled();
  });
});
