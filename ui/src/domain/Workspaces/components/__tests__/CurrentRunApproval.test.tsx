import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CurrentRunApproval } from "../CurrentRunApproval";

window._env_ = {
  REACT_APP_AUTHORITY: "http://localhost/authority",
  REACT_APP_CLIENT_ID: "client-id",
  REACT_APP_REDIRECT_URI: "http://localhost/redirect",
  REACT_APP_SCOPE: "openid",
  REACT_APP_TERRAKUBE_API_URL: "http://localhost:8080/api/v1",
  REACT_APP_TERRAKUBE_VERSION: "test",
  REACT_APP_REGISTRY_URI: "http://localhost:8080/registry",
};

const patchMock = jest.fn();
const contextGetMock = jest.fn();

jest.mock("../../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { patch: (...args: unknown[]) => patchMock(...args) },
  axiosAuxiliary: { get: (...args: unknown[]) => contextGetMock(...args) },
}));

const planOutput = (actions: string[][]) => ({
  "100": actions.map((a, i) => ({ address: `aws_thing.t${i}`, actions: a, action: a[0] })),
});

type Overrides = Partial<React.ComponentProps<typeof CurrentRunApproval>>;

const renderApproval = (overrides: Overrides = {}) => {
  const props = {
    organizationId: "org-1",
    jobId: "7",
    status: "waitingApproval",
    canApprove: true,
    permissionsLoaded: true,
    approvalTeam: null,
    onChanged: jest.fn(),
    ...overrides,
  };
  const view = render(<CurrentRunApproval {...props} />);
  return {
    ...view,
    props,
    rerenderWith: (next: Overrides) => view.rerender(<CurrentRunApproval {...props} {...next} />),
  };
};

const ready = () => waitFor(() => expect(screen.getByTestId("current-run-approve")).toBeEnabled());

describe("CurrentRunApproval", () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  beforeEach(() => {
    jest.clearAllMocks();
    contextGetMock.mockResolvedValue({
      data: { policyEvaluation: { softMandatoryViolations: 0 }, planStructuredOutput: planOutput([["create"]]) },
    });
    patchMock.mockResolvedValue({});
  });

  it("offers Approve and Discard to users with the approve permission", async () => {
    renderApproval();
    await ready();
    expect(screen.getByTestId("current-run-discard")).toBeEnabled();
  });

  it("only shows the status and no actions to users without the approve permission", () => {
    renderApproval({ canApprove: false });
    expect(screen.queryByTestId("current-run-approve")).toBeNull();
    expect(screen.getByText(/do not have permission/i)).toBeInTheDocument();
    expect(contextGetMock).not.toHaveBeenCalled();
  });

  it("shows nothing about permissions until they are known", () => {
    renderApproval({ canApprove: false, permissionsLoaded: false });
    expect(screen.queryByText(/permission/i)).toBeNull();
  });

  it("names the team the template asks to approve", async () => {
    renderApproval({ approvalTeam: "SRE" });
    await ready();
    expect(screen.getByText("SRE")).toBeInTheDocument();
  });

  it.each([
    ["soft", { softMandatoryViolations: 2 }],
    ["hard", { hardMandatoryViolations: 1 }],
    ["hard in one policy set", { results: [{ hardMandatoryViolations: 1 }] }],
  ])(
    "does not offer Approve when policy has %s violations, but still lets the run be discarded",
    async (_n, policyEvaluation) => {
      contextGetMock.mockResolvedValue({ data: { policyEvaluation } });
      renderApproval();
      expect(await screen.findByText(/cannot be approved from here/)).toBeInTheDocument();
      expect(screen.queryByTestId("current-run-approve")).toBeNull();
      expect(screen.getByTestId("current-run-discard")).toBeEnabled();
    }
  );

  it("does not offer approval while the policy result cannot be read, and keeps Discard", async () => {
    jest.useFakeTimers();
    contextGetMock.mockRejectedValue(new Error("boom"));
    renderApproval();
    expect(await screen.findByText(/Could not read the plan and policy results yet/)).toBeInTheDocument();
    expect(screen.queryByText(/cannot be approved from here/)).toBeNull();
    expect(screen.queryByTestId("current-run-approve")).toBeNull();
    expect(screen.getByTestId("current-run-discard")).toBeEnabled();
  });

  it("retries a failed context read and offers Approve once it succeeds", async () => {
    jest.useFakeTimers();
    contextGetMock.mockRejectedValueOnce({ response: { status: 404 } });
    renderApproval();
    expect(await screen.findByText(/trying again/)).toBeInTheDocument();

    await act(async () => {
      jest.advanceTimersByTime(1000);
    });

    await waitFor(() => expect(screen.getByTestId("current-run-approve")).toBeEnabled());
    expect(contextGetMock).toHaveBeenCalledTimes(2);
  });

  it("gives up after a bounded number of attempts and offers to check again", async () => {
    jest.useFakeTimers();
    contextGetMock.mockRejectedValue(new Error("down"));
    renderApproval();
    for (let i = 0; i < 6; i++) {
      await act(async () => {
        jest.advanceTimersByTime(8000);
      });
    }
    expect(contextGetMock).toHaveBeenCalledTimes(6);
    expect(await screen.findByText(/Could not read the plan and policy results\./)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Check again" }));
    await waitFor(() => expect(contextGetMock).toHaveBeenCalledTimes(7));
  });

  it("tells the approver what the plan changes in the confirmation", async () => {
    contextGetMock.mockResolvedValue({
      data: {
        policyEvaluation: {},
        planStructuredOutput: planOutput([["create"], ["update"], ["delete"], ["delete", "create"]]),
      },
    });
    renderApproval();
    await ready();

    fireEvent.click(screen.getByTestId("current-run-approve"));

    expect(await screen.findByText(/1 to add, 1 to change, 1 to destroy, 1 to replace/)).toBeInTheDocument();
    expect(screen.getByText("1 resource is destroyed.")).toBeInTheDocument();
  });

  it("says when the plan summary is not available instead of showing a bare confirmation", async () => {
    contextGetMock.mockResolvedValue({ data: { policyEvaluation: {} } });
    renderApproval();
    await ready();

    fireEvent.click(screen.getByTestId("current-run-approve"));

    expect(await screen.findByText(/plan summary is not available/)).toBeInTheDocument();
  });

  it("asks for confirmation, then approves the run", async () => {
    const { props } = renderApproval();
    await ready();

    fireEvent.click(screen.getByTestId("current-run-approve"));
    expect(patchMock).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByTestId("current-run-confirm"));

    await waitFor(() => expect(props.onChanged).toHaveBeenCalled());
    expect(patchMock).toHaveBeenCalledWith(
      "organization/org-1/job/7",
      { data: { type: "job", id: "7", attributes: { status: "approved" } } },
      { headers: { "Content-Type": "application/vnd.api+json" } }
    );
  });

  it("discards the run as rejected", async () => {
    const { props } = renderApproval();
    await ready();

    fireEvent.click(screen.getByTestId("current-run-discard"));
    fireEvent.click(await screen.findByTestId("current-run-confirm"));

    await waitFor(() => expect(props.onChanged).toHaveBeenCalled());
    expect(patchMock.mock.calls[0][1].data.attributes.status).toBe("rejected");
  });

  it("does not reload the page data when the request fails", async () => {
    patchMock.mockRejectedValue({ response: { data: { errors: [{ detail: "nope" }] } } });
    const { props } = renderApproval();
    await ready();

    fireEvent.click(screen.getByTestId("current-run-approve"));
    fireEvent.click(await screen.findByTestId("current-run-confirm"));

    await waitFor(() => expect(patchMock).toHaveBeenCalled());
    expect(props.onChanged).not.toHaveBeenCalled();
  });

  it("cannot be dismissed while the request is in flight", async () => {
    patchMock.mockReturnValue(new Promise(() => {}));
    renderApproval();
    await ready();

    fireEvent.click(screen.getByTestId("current-run-approve"));
    fireEvent.click(await screen.findByTestId("current-run-confirm"));

    await waitFor(() => expect(screen.getByRole("button", { name: "Back" })).toBeDisabled());
  });

  it("closes the confirmation, without sending, when the run is no longer waiting", async () => {
    const { rerenderWith } = renderApproval();
    await ready();
    fireEvent.click(screen.getByTestId("current-run-approve"));
    await screen.findByTestId("current-run-confirm");

    rerenderWith({ status: "approved" });

    await waitFor(() => expect(screen.queryByTestId("current-run-confirm")).toBeNull());
    expect(patchMock).not.toHaveBeenCalled();
  });
});
