import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { apiPost } from "@/modules/api/apiWrapper";
import { NotificationConfigurationList } from "../NotificationConfigurationList";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: { post: jest.fn(), get: jest.fn(), delete: jest.fn() },
  getErrorMessage: jest.fn(() => "error"),
  isPermissionError: jest.fn(() => false),
}));
jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));
jest.mock("../EditNotificationConfiguration", () => ({
  EditNotificationConfiguration: () => <div data-testid="edit-notification-configuration" />,
}));

const graphqlResponse = {
  isError: false,
  responseCode: 200,
  data: {
    organization: {
      edges: [
        {
          node: {
            notificationConfiguration: {
              edges: [
                {
                  node: {
                    id: "config-1",
                    name: "Org Slack Alerts",
                    channelType: "SLACK",
                    destinationUrl: "https://hooks.slack.com/services/X",
                    active: true,
                    workspace: { edges: [] },
                    triggers: { edges: [{ node: { id: "t1", jobStatus: "failed" } }] },
                  },
                },
                {
                  node: {
                    id: "config-2",
                    name: "Workspace Webhook",
                    channelType: "WEBHOOK",
                    destinationUrl: "https://example.com/hook",
                    active: true,
                    workspace: { edges: [{ node: { id: "ws-1" } }] },
                    triggers: { edges: [] },
                  },
                },
              ],
            },
          },
        },
      ],
    },
  },
};

describe("NotificationConfigurationList", () => {
  beforeEach(() => {
    (apiPost as jest.Mock).mockResolvedValue(graphqlResponse);
  });

  it("shows org-level configs plus workspace-scoped configs for the current workspace", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" workspaceId="ws-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.getByText("Workspace Webhook")).toBeInTheDocument();
  });

  it("shows only org-level configs when no workspaceId is provided", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.queryByText("Workspace Webhook")).not.toBeInTheDocument();
  });

  it("groups a workspace's page into this-workspace and organization-wide sections", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" workspaceId="ws-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "This workspace (1)" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Organization-wide (1)" })).toBeInTheDocument();
    // Organization-wide rows are managed from the organization, so they carry no row actions here.
    expect(screen.getByRole("button", { name: "Edit Workspace Webhook" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit Org Slack Alerts" })).not.toBeInTheDocument();
  });

  it("puts the add action in the header and names the notification in row actions", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.getAllByRole("button", { name: /Add notification/ })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Edit Org Slack Alerts" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Delete Org Slack Alerts" })).toBeEnabled();
  });

  it("does not show a this-workspace section on the organization-level page", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.queryByRole("heading", { name: /This workspace/ })).not.toBeInTheDocument();
  });

  it("does not offer an override action - workspace and org configs are purely additive now", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" workspaceId="ws-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /override/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/already overridden/i)).not.toBeInTheDocument();
  });

  it("surfaces an error instead of silently rendering an empty list when the GraphQL query fails", async () => {
    // A GraphQL error still resolves the HTTP call (200 OK with an "errors" array,
    // no top-level "data") - apiPost's dataWrapped unwrapping then yields undefined.
    // This is exactly the shape that let a broken query silently render "no
    // notifications" instead of a visible failure.
    (apiPost as jest.Mock).mockResolvedValue({ isError: false, responseCode: 200, data: undefined });

    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(apiPost).toHaveBeenCalled());
    expect(screen.queryByText("Org Slack Alerts")).not.toBeInTheDocument();
  });
});
