import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { OrgNotifications } from "../Notifications";

jest.mock("@/domain/Notifications/NotificationConfigurationList", () => ({
  NotificationConfigurationList: ({ orgId, workspaceId }: { orgId: string; workspaceId?: string }) => (
    <div data-testid="notification-list">
      {orgId}:{String(workspaceId)}
    </div>
  ),
}));

describe("OrgNotifications", () => {
  it("renders the shared list scoped to the org only, no workspaceId", () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/notifications"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/notifications"
            element={<OrgNotifications managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByTestId("notification-list")).toHaveTextContent("org-1:undefined");
  });
});
