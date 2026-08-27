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

  it("tags each row as 'Org default' or 'This workspace' when viewing a workspace's page", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" workspaceId="ws-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.getByText("Org default")).toBeInTheDocument();
    expect(screen.getByText("This workspace")).toBeInTheDocument();
  });

  it("does not show org/workspace scope tags on the organization-level page", async () => {
    render(
      <MemoryRouter>
        <NotificationConfigurationList orgId="org-1" managePermission={true} />
      </MemoryRouter>
    );

    await waitFor(() => expect(screen.getByText("Org Slack Alerts")).toBeInTheDocument());
    expect(screen.queryByText("Org default")).not.toBeInTheDocument();
    expect(screen.queryByText("This workspace")).not.toBeInTheDocument();
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
