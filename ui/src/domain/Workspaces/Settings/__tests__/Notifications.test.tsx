import { render, screen } from "@testing-library/react";
import { WorkspaceNotifications } from "../Notifications";

jest.mock("@/domain/Notifications/NotificationConfigurationList", () => ({
  NotificationConfigurationList: ({ orgId, workspaceId }: { orgId: string; workspaceId?: string }) => (
    <div data-testid="notification-list">
      {orgId}:{workspaceId}
    </div>
  ),
}));
jest.mock("@/domain/Notifications/NotificationDeliveryHistory", () => ({
  NotificationDeliveryHistory: ({ workspaceId }: { workspaceId: string }) => (
    <div data-testid="notification-delivery-history">{workspaceId}</div>
  ),
}));

describe("WorkspaceNotifications", () => {
  it("renders the shared list scoped to this workspace's organization and id", () => {
    const workspace = {
      id: "ws-1",
      relationships: { organization: { data: { id: "org-1" } } },
    } as any;

    render(<WorkspaceNotifications workspace={workspace} manageWorkspace={true} />);

    expect(screen.getByTestId("notification-list")).toHaveTextContent("org-1:ws-1");
  });
});
