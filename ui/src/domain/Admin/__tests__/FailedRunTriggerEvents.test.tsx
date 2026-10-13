import { render, screen } from "@testing-library/react";
import { FailedRunTriggerEvents } from "../FailedRunTriggerEvents";

const listMock = jest.fn();

jest.mock("@/modules/admin/runTriggerEventService", () => ({
  listRunTriggerEvents: (...args: unknown[]) => listMock(...args),
  replayRunTriggerEvent: jest.fn(),
  getAdminErrorMessage: () => "error",
}));

// PageWrapper deep-imports an ESM-only antd submodule Jest can't transform, so pages built on it mock it.
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: function PageWrapper({ children }: { children?: React.ReactNode }) {
    return <div>{children}</div>;
  },
}));

describe("FailedRunTriggerEvents", () => {
  beforeEach(() => listMock.mockReset());

  it("lists every organization's failed events, naming the organization", async () => {
    listMock.mockResolvedValue([
      {
        id: "event-1",
        jobId: 900,
        workspaceName: "network",
        status: "FAILED",
        attemptCount: 5,
        organizationName: "acme",
      },
    ]);
    render(<FailedRunTriggerEvents />);

    expect(await screen.findByText("#900 (network)")).toBeInTheDocument();
    expect(screen.getByText("acme")).toBeInTheDocument();
    expect(listMock).toHaveBeenCalledWith(undefined, undefined);
  });

  it("shows an access-denied alert to someone who is not an instance admin", async () => {
    listMock.mockRejectedValue({ response: { status: 403 } });
    render(<FailedRunTriggerEvents />);

    expect(await screen.findByText("Access Denied")).toBeInTheDocument();
  });
});
