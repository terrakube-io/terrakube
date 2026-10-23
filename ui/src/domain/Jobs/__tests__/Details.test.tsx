import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { message } from "antd";
import { DetailsJob } from "../Details";
import { stepLogCache } from "../stepLogCache";

// The step-log cache is module-level and would otherwise leak a fetched log from one test into the
// next (they reuse step ids like "step-1").
beforeEach(() => {
  stepLogCache.clear();
});

window._env_ = {
  REACT_APP_AUTHORITY: "http://localhost/authority",
  REACT_APP_CLIENT_ID: "client-id",
  REACT_APP_REDIRECT_URI: "http://localhost/redirect",
  REACT_APP_SCOPE: "openid",
  REACT_APP_TERRAKUBE_API_URL: "http://localhost:8080/api/v1",
  REACT_APP_TERRAKUBE_VERSION: "test",
  REACT_APP_REGISTRY_URI: "http://localhost:8080/registry",
};

const getMock = jest.fn();
const patchMock = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => getMock(...args), patch: (...args: unknown[]) => patchMock(...args) },
  axiosClient: { get: (...args: unknown[]) => getMock(...args) },
  axiosAuxiliary: {
    get: (...args: unknown[]) => getMock(...args),
    head: (...args: unknown[]) => getMock(...args),
  },
}));

const useStructuredOutputStreamMock = jest.fn();

jest.mock("../../../hooks", () => ({
  ...jest.requireActual("../../../hooks"),
  useStructuredOutputStream: (...args: unknown[]) => useStructuredOutputStreamMock(...args),
}));

describe("DetailsJob apply structured output", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
  });

  it("uses the live structured-output stream while the job is running, not just the poll", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: {} });
      }

      return Promise.resolve({
        data: {
          data: {
            id: "1",
            attributes: { status: "running" },
          },
          included: [
            {
              id: "step-2",
              type: "step",
              attributes: { name: "Apply", status: "running", stepNumber: "2" },
            },
          ],
        },
      });
    });

    useStructuredOutputStreamMock.mockReturnValue({
      phase: "apply",
      changes: {
        "step-2": [
          {
            address: "aws_instance.live",
            action: "create",
            actions: ["create"],
            after: { id: "i-live" },
            status: "applying",
          },
        ],
      },
      jobDiagnostics: {},
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /aws_instance\.live/i })).toBeInTheDocument();
    });
  });

  it("renders the structured component for an apply step when applyStructuredOutput is present", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({
          data: {
            applyStructuredOutput: {
              "step-2": [
                {
                  address: "aws_instance.example",
                  action: "create",
                  actions: ["create"],
                  after: { id: "i-123" },
                  status: "applied",
                },
              ],
            },
          },
        });
      }

      return Promise.resolve({
        data: {
          data: {
            id: "1",
            attributes: { status: "completed" },
          },
          included: [
            {
              id: "step-2",
              type: "step",
              attributes: { name: "Apply", status: "completed", stepNumber: "2" },
            },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /aws_instance\.example/i })).toBeInTheDocument();
    });
  });

  it("does not claim 'no changes needed' while a plan step is still running with no changes streamed yet", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: {} });
      }

      return Promise.resolve({
        data: {
          data: {
            id: "1",
            attributes: { status: "running" },
          },
          included: [
            {
              id: "step-1",
              type: "step",
              attributes: { name: "Plan", status: "running", stepNumber: "1" },
            },
          ],
        },
      });
    });

    useStructuredOutputStreamMock.mockReturnValue({
      phase: "plan",
      changes: { "step-1": [] },
      jobDiagnostics: {},
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => {
      expect(screen.getByText(/plan is running/i)).toBeInTheDocument();
    });

    expect(
      screen.queryByText("Your infrastructure matches the configuration — no changes needed.")
    ).not.toBeInTheDocument();
  });

  it("shows 'temporarily unavailable' (never a false 'No changes') when structured context is missing after a plan with changes", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: {} });
      }
      if (url.includes("/step/")) {
        return Promise.resolve({
          data: "Terraform will perform the following actions:\n\nPlan: 1 to add, 0 to change, 0 to destroy.\n",
        });
      }

      return Promise.resolve({
        data: {
          data: {
            id: "1",
            attributes: { status: "completed" },
          },
          included: [
            {
              id: "step-1",
              type: "step",
              attributes: { name: "Plan", status: "completed", stepNumber: "1" },
            },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => {
      expect(screen.getByText("Structured output temporarily unavailable")).toBeInTheDocument();
    });
    expect(
      screen.queryByText("Your infrastructure matches the configuration — no changes needed.")
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /retry/i })).toBeInTheDocument();
  });
});

describe("DetailsJob terminal-status context reconciliation", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
  });

  it("loads the final structured diff after a terminal transition without a browser refresh", async () => {
    let jobStatus: "running" | "completed" = "running";
    let contextPersisted = false;

    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve(
          contextPersisted
            ? {
                data: {
                  structuredOutputStatus: { state: "PERSISTED" },
                  applyStructuredOutput: {
                    "step-2": [
                      {
                        address: "aws_instance.reconciled",
                        action: "create",
                        actions: ["create"],
                        after: { id: "i-1" },
                        status: "applied",
                      },
                    ],
                  },
                },
              }
            : { data: {} }
        );
      }
      return Promise.resolve({
        data: {
          data: { id: "1", attributes: { status: jobStatus } },
          included: [{ id: "step-2", type: "step", attributes: { name: "Apply", status: jobStatus, stepNumber: "2" } }],
        },
      });
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(screen.getByText(/Apply/)).toBeInTheDocument());

    jobStatus = "completed";
    contextPersisted = true;

    await waitFor(() => expect(screen.getByRole("button", { name: /aws_instance\.reconciled/i })).toBeInTheDocument(), {
      timeout: 8000,
    });
    expect(screen.queryByText("Structured output temporarily unavailable")).not.toBeInTheDocument();
  }, 12000);

  it("keeps the page and other steps usable when one archived step log returns 503", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: { structuredOutputStatus: { state: "PERSISTED" } } });
      }
      if (url.includes("/step/step-1")) {
        return Promise.reject({ response: { status: 503 }, isAxiosError: true });
      }
      if (url.includes("/step/step-2")) {
        return Promise.resolve({ data: "step two log output", headers: {} });
      }
      return Promise.resolve({
        data: {
          data: { id: "1", attributes: { status: "completed" } },
          included: [
            {
              id: "step-1",
              type: "step",
              attributes: { name: "Terraform Plan", status: "completed", stepNumber: "1" },
            },
            {
              id: "step-2",
              type: "step",
              attributes: { name: "Terraform Apply", status: "completed", stepNumber: "2" },
            },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => expect(screen.getByText(/Terraform Plan/)).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText("Could not load this step’s log.")).toBeInTheDocument(), {
      timeout: 12000,
    });

    // The page shell and the healthy step's log are still there.
    expect(screen.queryByText("Loading Job...")).not.toBeInTheDocument();
    expect(screen.getByText(/Terraform Apply/)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /retry/i }).length).toBeGreaterThan(0);
  }, 20000);

  it("keeps the page usable when context stays unavailable (503) - console shown, no spinner, never 'No changes'", async () => {
    let jobStatus: "running" | "completed" = "running";

    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.reject({ response: { status: 503 }, isAxiosError: true });
      }
      if (url.includes("/step/")) {
        return Promise.resolve({ data: "Plan: 1 to add, 0 to change, 0 to destroy.\n" });
      }
      return Promise.resolve({
        data: {
          data: { id: "1", attributes: { status: jobStatus } },
          included: [{ id: "step-1", type: "step", attributes: { name: "Plan", status: jobStatus, stepNumber: "1" } }],
        },
      });
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(screen.getByText(/Plan/)).toBeInTheDocument());

    jobStatus = "completed";

    await waitFor(() => expect(screen.getByText("Structured output temporarily unavailable")).toBeInTheDocument(), {
      timeout: 8000,
    });
    expect(
      screen.queryByText("Your infrastructure matches the configuration — no changes needed.")
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Loading Job...")).not.toBeInTheDocument();
  }, 12000);
});

describe("DetailsJob SSE reconnect behavior", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReset();
  });

  it("keeps Job Details rendered and HTTP polling delivering structured output while SSE is disconnected", async () => {
    // The SSE hook returns null the whole time (connection interrupted / never established).
    useStructuredOutputStreamMock.mockReturnValue(null);
    let contextHasData = false;

    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve(
          contextHasData
            ? {
                data: {
                  structuredOutputStatus: { state: "PERSISTED" },
                  applyStructuredOutput: {
                    "step-2": [
                      {
                        address: "aws_instance.via_poll",
                        action: "create",
                        actions: ["create"],
                        after: { id: "i-9" },
                        status: "applied",
                      },
                    ],
                  },
                },
              }
            : { data: {} }
        );
      }
      return Promise.resolve({
        data: {
          data: { id: "1", attributes: { status: "running" } },
          included: [{ id: "step-2", type: "step", attributes: { name: "Apply", status: "running", stepNumber: "2" } }],
        },
      });
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(screen.getByText(/Apply/)).toBeInTheDocument());

    contextHasData = true;

    // No SSE event ever arrives; the 5s refreshJobDetails HTTP poll must still pick up the diff.
    await waitFor(() => expect(screen.getByRole("button", { name: /aws_instance\.via_poll/i })).toBeInTheDocument(), {
      timeout: 8000,
    });
    expect(screen.queryByText("Loading Job...")).not.toBeInTheDocument();
  }, 12000);

  it("keeps the last known structured output when the job leaves running and the live channel disables", async () => {
    let jobStatus: "running" | "completed" = "running";

    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: {} });
      }

      return Promise.resolve({
        data: {
          data: {
            id: "1",
            attributes: { status: jobStatus },
          },
          included: [
            {
              id: "step-2",
              type: "step",
              attributes: { name: "Apply", status: jobStatus, stepNumber: "2" },
            },
          ],
        },
      });
    });

    // Mirrors the real useStructuredOutputStream/useEventStream: while enabled, it keeps
    // returning the *same* value reference across re-renders (the real hook only produces a
    // new one via setValue when an SSE message actually arrives) - a fresh object literal on
    // every call, by contrast, would make Details.tsx's `useEffect(() => {...},
    // [liveStructuredOutput])` see a "changed" dependency on every single render (referential
    // inequality) and re-run its state-setting merge every time, which triggers another
    // re-render, which calls this mock again, forever - an infinite loop entirely of the
    // test's own making, not a symptom of anything under test.
    const livePayload = {
      phase: "apply",
      changes: {
        "step-2": [
          {
            address: "aws_instance.live",
            action: "create",
            actions: ["create"],
            after: { id: "i-live" },
            status: "applying",
          },
        ],
      },
      jobDiagnostics: {},
    };

    // It resets to `initial` (null) the instant the caller disables it - Details.tsx does that
    // the moment the job leaves "running" (see useEventStream's unconditional
    // `setValue(initial)` on every [url, enabled] change). The merge effect in Details.tsx must
    // treat that reset as "no new live data this render", not as "clear whatever structured
    // output is already showing".
    useStructuredOutputStreamMock.mockImplementation((options: { enabled: boolean }) =>
      options.enabled ? livePayload : null
    );

    render(<DetailsJob jobId="1" />);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /aws_instance\.live/i })).toBeInTheDocument();
    });

    jobStatus = "completed";

    // Details.tsx polls the job every 5s (usePolling); real timers here (no fake-timer
    // juggling with the interval that was already scheduled at mount) so wait past one cycle
    // for the transition to actually happen.
    await waitFor(
      () => {
        expect(screen.getAllByText("Completed")).toHaveLength(2); // run header tag and Apply step
      },
      { timeout: 8000 }
    );

    expect(screen.getByRole("button", { name: /aws_instance\.live/i })).toBeInTheDocument();
  }, 10000);
});

describe("DetailsJob progressive render", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
  });

  it("paints steps before the step-log fetch resolves and never blocks first paint on it", async () => {
    const logPending = new Promise(() => {}); // stays pending for the whole test

    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: {} });
      }
      if (url.includes("/tfoutput/")) {
        return logPending; // never resolves during the assertion window
      }
      return Promise.resolve({
        data: {
          data: { id: "1", attributes: { status: "completed" } },
          included: [
            { id: "step-1", type: "step", attributes: { name: "Plan", status: "completed", stepNumber: "1" } },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);

    // The step label is on screen even though its log fetch is still pending.
    await waitFor(() => {
      expect(screen.getByText(/Plan/)).toBeInTheDocument();
    });
  });
});

describe("DetailsJob no-change apply semantics", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
  });

  const twoStepJob = (status: string) => ({
    data: {
      data: { id: "1", attributes: { status } },
      included: [
        { id: "plan-1", type: "step", attributes: { name: "Terraform Plan", status, stepNumber: "1" } },
        { id: "apply-1", type: "step", attributes: { name: "Terraform Apply", status, stepNumber: "2" } },
      ],
    },
  });

  const noChangeContext = {
    data: {
      structuredOutputStatus: { state: "PERSISTED" },
      noChangePlan: { planStepId: "plan-1" },
      planStructuredOutput: { "plan-1": [] },
    },
  };

  it("renders a no-op Apply state (not a warning) for a persisted empty plan with no apply rows", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) return Promise.resolve(noChangeContext);
      if (url.includes("/step/"))
        return Promise.resolve({ data: "Apply complete! Resources: 0 added, 0 changed, 0 destroyed.", headers: {} });
      return Promise.resolve(twoStepJob("completed"));
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => expect(screen.getByText(/Apply completed with no changes/)).toBeInTheDocument());
    expect(screen.getByText("Your infrastructure matches the configuration — no changes needed.")).toBeInTheDocument();
    expect(screen.queryByText("Structured output temporarily unavailable")).not.toBeInTheDocument();
  });

  it("retains the no-change/no-op presentation when a later context read returns 503", async () => {
    let jobStatus = "running";
    let contextCalls = 0;
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        contextCalls += 1;
        return contextCalls === 1
          ? Promise.resolve(noChangeContext)
          : Promise.reject({ response: { status: 503 }, isAxiosError: true });
      }
      if (url.includes("/step/"))
        return Promise.resolve({ data: "Apply complete! Resources: 0 added, 0 changed, 0 destroyed.", headers: {} });
      return Promise.resolve(twoStepJob(jobStatus));
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(contextCalls).toBeGreaterThan(0));

    jobStatus = "completed"; // the 5s poll re-fetches job (terminal) + context (now 503)

    // Plan step reaching its terminal "no changes needed" proves the job transitioned AND a
    // later 503 context read has already happened - the empty-plan evidence must have survived it.
    await waitFor(
      () =>
        expect(
          screen.getByText("Your infrastructure matches the configuration — no changes needed.")
        ).toBeInTheDocument(),
      { timeout: 10000 }
    );
    expect(contextCalls).toBeGreaterThan(1);
    expect(screen.getByText(/Apply completed with no changes/)).toBeInTheDocument();
    expect(screen.queryByText("Structured output temporarily unavailable")).not.toBeInTheDocument();
  }, 15000);

  it("still warns for Apply when the plan had changes but the apply snapshot is unavailable", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({
          data: {
            structuredOutputStatus: { state: "PERSISTED" },
            planStructuredOutput: {
              "plan-1": [{ address: "aws_instance.x", action: "create", actions: ["create"], after: { id: "i-1" } }],
            },
          },
        });
      }
      if (url.includes("/step/"))
        return Promise.resolve({ data: "Terraform will perform the following actions:", headers: {} });
      return Promise.resolve(twoStepJob("completed"));
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => expect(screen.getByText("Structured output temporarily unavailable")).toBeInTheDocument());
    expect(screen.queryByText(/Apply completed with no changes/)).not.toBeInTheDocument();
  });

  it("does not show 'No changes' for an unavailable context with no prior no-change evidence", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) return Promise.reject({ response: { status: 503 }, isAxiosError: true });
      if (url.includes("/step/")) return Promise.resolve({ data: "Refreshing state...", headers: {} });
      return Promise.resolve(twoStepJob("completed"));
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() =>
      expect(screen.getAllByText("Structured output temporarily unavailable").length).toBeGreaterThan(0)
    );
    expect(
      screen.queryByText("Your infrastructure matches the configuration — no changes needed.")
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/Apply completed with no changes/)).not.toBeInTheDocument();
  });

  it("shows the real console diagnostic for a genuinely failed apply even with a no-change plan marker", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) return Promise.resolve(noChangeContext);
      if (url.includes("/step/apply-1"))
        return Promise.resolve({ data: "Error: the Terrakube executor could not finish this operation", headers: {} });
      if (url.includes("/step/")) return Promise.resolve({ data: "no changes", headers: {} });
      return Promise.resolve({
        data: {
          data: { id: "1", attributes: { status: "failed" } },
          included: [
            {
              id: "plan-1",
              type: "step",
              attributes: { name: "Terraform Plan", status: "completed", stepNumber: "1" },
            },
            { id: "apply-1", type: "step", attributes: { name: "Terraform Apply", status: "failed", stepNumber: "2" } },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() =>
      expect(screen.getByText(/the Terrakube executor could not finish this operation/)).toBeInTheDocument()
    );
    expect(screen.queryByText(/Apply completed with no changes/)).not.toBeInTheDocument();
  });
});

describe("plan approval attribution", () => {
  it.each([null, "reviewer@example.com"])("shows approval only when recorded (%s)", async (approvedBy) => {
    useStructuredOutputStreamMock.mockReturnValue(null);
    getMock.mockImplementation((url: string) =>
      Promise.resolve({
        data: url.includes("/context/v1/")
          ? {}
          : {
              data: {
                id: "3554",
                attributes: {
                  status: "completed",
                  createdBy: "author@example.com",
                  approvedBy,
                  approvedAt: approvedBy ? "2026-09-16T10:30:00Z" : null,
                },
              },
              included: [],
            },
      })
    );
    render(<DetailsJob jobId="3554" />);
    await screen.findByText("author@example.com");
    if (approvedBy) {
      expect(screen.getByText(approvedBy)).toBeInTheDocument();
      expect(screen.getByText(/Approved by/)).toBeInTheDocument();
      expect(document.querySelector('time[datetime="2026-09-16T10:30:00Z"]')).toBeInTheDocument();
    } else {
      expect(screen.queryByText(/Approved by/)).not.toBeInTheDocument();
    }
  });
});

describe("run closed by Terrakube", () => {
  const mockJob = (output?: string, status = "cancelled") => {
    useStructuredOutputStreamMock.mockReturnValue(null);
    sessionStorage.setItem("ORGANIZATION_ARCHIVE", "org-1");
    getMock.mockImplementation((url: string) =>
      Promise.resolve({
        data: url.includes("/context/v1/")
          ? {}
          : {
              data: { id: "7", attributes: { status, createdBy: "author@example.com", output } },
              included: [],
            },
      })
    );
  };

  it("shows why Terrakube closed the run", async () => {
    mockJob("Closed by Terrakube: Approval was not given within 48 hours, the run was cancelled.");
    render(<DetailsJob jobId="7" />);

    expect(await screen.findByText("Run closed by Terrakube")).toBeInTheDocument();
    expect(screen.getByText("Approval was not given within 48 hours, the run was cancelled.")).toBeInTheDocument();
  });

  it("shows the notice for a run that failed", async () => {
    mockJob("Closed by Terrakube: The run did not finish within 6 hours and was closed.", "failed");
    render(<DetailsJob jobId="7" />);

    expect(await screen.findByText("Run closed by Terrakube")).toBeInTheDocument();
  });

  it("shows no notice for a run that is not over, whatever its output says", async () => {
    mockJob("Closed by Terrakube: The run did not finish within 6 hours and was closed.", "completed");
    render(<DetailsJob jobId="7" />);

    await waitFor(() => expect(screen.getByText("Run #7")).toBeInTheDocument());
    expect(screen.queryByText("Run closed by Terrakube")).not.toBeInTheDocument();
  });

  it("shows no notice for a run that was closed some other way", async () => {
    mockJob("Step 1 completed");
    render(<DetailsJob jobId="7" />);

    await waitFor(() => expect(screen.getByText("Run #7")).toBeInTheDocument());
    expect(screen.queryByText("Run closed by Terrakube")).not.toBeInTheDocument();
  });
});

describe("run actions", () => {
  it("asks before approving, states what the plan destroys, then approves", async () => {
    useStructuredOutputStreamMock.mockReturnValue(null);
    sessionStorage.setItem("ORGANIZATION_ARCHIVE", "org-1");
    getMock.mockImplementation((url: string) =>
      Promise.resolve({
        data: url.includes("/context/v1/")
          ? {
              planStructuredOutput: {
                "step-1": [{ address: "aws_instance.old", action: "delete", actions: ["delete"] }],
              },
            }
          : {
              data: { id: "7", attributes: { status: "waitingApproval", createdBy: "author@example.com" } },
              included: [
                { id: "step-1", type: "step", attributes: { name: "Plan", status: "completed", stepNumber: "1" } },
              ],
            },
      })
    );
    patchMock.mockResolvedValue({});

    render(<DetailsJob jobId="7" canApprove />);
    await waitFor(() => expect(screen.getByText(/0 to add, 0 to change, 1 to destroy/)).toBeInTheDocument());
    fireEvent.click(screen.getByTestId("approve-run"));

    expect(screen.getByText("Approve and apply this run?")).toBeInTheDocument();
    expect(screen.getByText(/1 resource is destroyed/)).toBeInTheDocument();
    expect(patchMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId("confirm-run-action"));
    await waitFor(() =>
      expect(patchMock).toHaveBeenCalledWith(
        "organization/org-1/job/7",
        { data: { type: "job", id: "7", attributes: { status: "approved" } } },
        expect.anything()
      )
    );
  });

  const mockJobStatus = (getStatus: () => string) =>
    getMock.mockImplementation((url: string) =>
      Promise.resolve({
        data: url.includes("/context/v1/")
          ? {}
          : { data: { id: "7", attributes: { status: getStatus(), createdBy: "author@example.com" } }, included: [] },
      })
    );

  const runPoll = async () => {
    const poll = intervalSpy.mock.calls.find(([, delay]) => delay === 5000)?.[0] as () => void;
    await act(async () => {
      poll();
    });
  };

  let intervalSpy: jest.SpyInstance;
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
    sessionStorage.setItem("ORGANIZATION_ARCHIVE", "org-1");
    patchMock.mockReset();
    intervalSpy = jest.spyOn(window, "setInterval");
  });
  afterEach(() => {
    intervalSpy.mockRestore();
    message.destroy();
  });

  it("closes the confirm modal without sending anything when polling moves the run on", async () => {
    let status = "running";
    mockJobStatus(() => status);
    render(<DetailsJob jobId="7" canApprove />);
    fireEvent.click(await screen.findByTestId("cancel-run"));
    expect(screen.getByText("Cancel this run?")).toBeInTheDocument();

    status = "completed";
    await runPoll();

    await waitFor(() => expect(screen.queryByText("Cancel this run?")).not.toBeInTheDocument());
    expect(await screen.findAllByText("This run changed status; nothing was sent.")).toHaveLength(1);
    expect(patchMock).not.toHaveBeenCalled();
  });

  it("closes the approve modal when the run is no longer waiting for approval", async () => {
    let status = "waitingApproval";
    mockJobStatus(() => status);
    render(<DetailsJob jobId="7" canApprove />);
    fireEvent.click(await screen.findByTestId("approve-run"));
    expect(screen.getByText("Approve and apply this run?")).toBeInTheDocument();

    status = "rejected";
    await runPoll();

    await waitFor(() => expect(screen.queryByText("Approve and apply this run?")).not.toBeInTheDocument());
    expect(screen.queryByTestId("confirm-run-action")).not.toBeInTheDocument();
  });

  it("closes the modal quietly when its own action moves the run on", async () => {
    let status = "running";
    mockJobStatus(() => status);
    patchMock.mockImplementation(() => {
      status = "cancelled";
      return Promise.resolve({});
    });
    render(<DetailsJob jobId="7" />);
    fireEvent.click(await screen.findByTestId("cancel-run"));
    fireEvent.click(screen.getByTestId("confirm-run-action"));

    await waitFor(() => expect(screen.queryByText("Cancel this run?")).not.toBeInTheDocument());
    expect(patchMock).toHaveBeenCalledTimes(1);
    expect(await screen.findByText("Run cancelled")).toBeInTheDocument();
    expect(screen.queryByText("This run changed status; nothing was sent.")).not.toBeInTheDocument();
  });

  it("drops a pending confirmation when the run on the page changes", async () => {
    mockJobStatus(() => "running");
    const { rerender } = render(<DetailsJob jobId="7" />);
    fireEvent.click(await screen.findByTestId("cancel-run"));
    expect(screen.getByText("Cancel this run?")).toBeInTheDocument();

    rerender(<DetailsJob jobId="8" />);

    await waitFor(() => expect(screen.queryByText("Cancel this run?")).not.toBeInTheDocument());
  });
});

describe("DetailsJob run diagnostics and output completeness (#3693)", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
  });

  const stepEntry = (id: string, name: string, number: string, extra: Record<string, unknown> = {}) => ({
    id,
    type: "step",
    attributes: { name, status: "completed", stepNumber: number, ...extra },
  });

  const jobResponse = (steps: unknown[]) => ({
    data: { data: { id: "1", attributes: { status: "completed" } }, included: steps },
  });

  const mockRun = (context: Record<string, unknown>, steps: unknown[]) => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) return Promise.resolve({ data: context });
      if (url.includes("/step/")) return Promise.resolve({ data: "log line", headers: {} });
      return Promise.resolve(jobResponse(steps));
    });
  };

  const planRows = {
    "plan-1": [
      {
        address: "aws_instance.web",
        action: "create",
        actions: ["create"],
        after: { id: "i-1" },
        diagnostics: [{ severity: "warning", summary: "Deprecated argument", detail: "Use 'x'." }],
      },
      {
        address: "aws_s3_bucket.logs",
        action: "no-op",
        actions: ["no-op"],
        diagnostics: [{ severity: "error", summary: "Bucket ACL is not supported", location: "s3.tf:12" }],
      },
    ],
  };

  it("lists every captured diagnostic in the run view while its resource rows are collapsed", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: true },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "ACKED" })]
    );

    render(<DetailsJob jobId="1" />);

    const panel = await screen.findByTestId("run-diagnostics");
    // The rows are collapsed (nothing expanded them), yet both diagnostics are readable.
    expect(panel).toHaveTextContent("aws_instance.web");
    expect(panel).toHaveTextContent("Deprecated argument");
    expect(panel).toHaveTextContent("aws_s3_bucket.logs");
    expect(panel).toHaveTextContent("Bucket ACL is not supported");
    expect(panel).toHaveTextContent("(s3.tf:12)");
    expect(panel).toHaveTextContent("1 error");
    expect(panel).toHaveTextContent("1 warning");
  });

  it("keeps the run view complete when a resource filter hides the rows", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: true },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "ACKED" })]
    );

    render(<DetailsJob jobId="1" />);
    await screen.findByTestId("run-diagnostics");

    fireEvent.change(screen.getByLabelText("Filter resources by address"), { target: { value: "no-such-resource" } });

    await waitFor(() => expect(screen.getByText("No resources match the current filters.")).toBeInTheDocument());
    const diagnostics = screen.getAllByTestId("run-diagnostic");
    expect(diagnostics).toHaveLength(2);
  });

  it("shows diagnostics for a plan that produced no rows, and does not call it 'no changes'", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: true },
        planStructuredOutput: { "plan-1": [] },
        jobDiagnostics: {
          "plan-1": [{ severity: "error", summary: "Invalid provider configuration", location: "providers.tf:4" }],
        },
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { status: "failed", deliveryState: "ACKED" })]
    );

    render(<DetailsJob jobId="1" />);

    const panel = await screen.findByTestId("run-diagnostics");
    expect(panel).toHaveTextContent("Invalid provider configuration");
    expect(panel).toHaveTextContent("(providers.tf:4)");
    expect(
      screen.queryByText("Your infrastructure matches the configuration — no changes needed.")
    ).not.toBeInTheDocument();
  });

  it("does not show a diagnostics view when the run captured none", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: true },
        planStructuredOutput: { "plan-1": [] },
        noChangePlan: { planStepId: "plan-1" },
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "ACKED" })]
    );

    render(<DetailsJob jobId="1" />);

    await screen.findByText("Your infrastructure matches the configuration — no changes needed.");
    expect(screen.queryByTestId("run-diagnostics")).not.toBeInTheDocument();
  });

  it("marks a step whose final output was acknowledged as final", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: true },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "ACKED" })]
    );

    render(<DetailsJob jobId="1" />);

    const notice = await screen.findByTestId("output-completeness");
    expect(notice).toHaveAttribute("data-state", "final");
  });

  it("shows output that is still being recovered as recovering, not final, even though rows exist", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "SPOOLED" })]
    );

    render(<DetailsJob jobId="1" />);

    const notice = await screen.findByTestId("output-completeness");
    expect(notice).toHaveAttribute("data-state", "recovering");
    expect(notice).toHaveTextContent("Recovering final output");
  });

  it("shows output that could not be recovered as lost, with the last progress still visible", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "EXPIRED" })]
    );

    render(<DetailsJob jobId="1" />);

    const notice = await screen.findByTestId("output-completeness");
    expect(notice).toHaveAttribute("data-state", "expired");
    expect(screen.getByRole("button", { name: /aws_instance\.web/i })).toBeInTheDocument();
  });

  it("does not treat a persisted progress snapshot as complete: it waits for the final output", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "NOT_DURABLE" })]
    );

    render(<DetailsJob jobId="1" />);

    const notice = await screen.findByTestId("output-completeness");
    expect(notice).toHaveAttribute("data-state", "awaiting-final");
  });

  it("settles on 'partial' once the re-checking window ends without a final output", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "NOT_DURABLE" })]
    );
    const realNow = Date.now.bind(Date);
    let offset = 0;
    const nowSpy = jest.spyOn(Date, "now").mockImplementation(() => realNow() + offset);

    try {
      render(<DetailsJob jobId="1" />);
      await waitFor(() =>
        expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "awaiting-final")
      );

      offset = 61_000; // the 60s window has now passed

      await waitFor(() => expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "partial"), {
        timeout: 6000,
      });
      expect(screen.getByRole("button", { name: /aws_instance\.web/i })).toBeInTheDocument();
    } finally {
      nowSpy.mockRestore();
    }
  }, 12000);

  it("does not treat the empty snapshot of a plan that is still running as 'no changes' for the apply", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
        planStructuredOutput: { "plan-1": [] },
      },
      [
        stepEntry("plan-1", "Terraform Plan", "1", { status: "running", deliveryState: "NOT_DURABLE" }),
        stepEntry("apply-1", "Terraform Apply", "2", { status: "completed", deliveryState: "NOT_DURABLE" }),
      ]
    );

    render(<DetailsJob jobId="1" />);

    await screen.findByText("Terraform Apply");
    expect(screen.queryByText(/Apply completed with no changes/)).not.toBeInTheDocument();
  });

  it("stops saying that it is recovering once the page gave up checking", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "SPOOLED" })]
    );
    const realNow = Date.now.bind(Date);
    let offset = 0;
    const nowSpy = jest.spyOn(Date, "now").mockImplementation(() => realNow() + offset);

    try {
      render(<DetailsJob jobId="1" />);
      await waitFor(() =>
        expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "recovering")
      );

      offset = 6 * 60_000; // past the 60s window and the extra time for output that is being recovered

      await waitFor(
        () => expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "recovery-stalled"),
        { timeout: 6000 }
      );
      expect(screen.getByTestId("output-completeness")).toHaveTextContent("reload it to check again");
    } finally {
      nowSpy.mockRestore();
    }
  }, 12000);

  describe("steps the template did not name", () => {
    // Without a `name:` in the template a step is called "Running Step100", which says nothing
    // about being a plan, so the delivery state has to be enough.
    const unnamedPlan = (extra: Record<string, unknown>) => [stepEntry("plan-1", "Running Step100", "100", extra)];

    it.each([
      ["ACKED", "final"],
      ["SPOOLED", "recovering"],
      ["EXPIRED", "expired"],
    ])("shows a step in delivery state %s as %s", async (deliveryState, expected) => {
      mockRun(
        {
          structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
          planStructuredOutput: planRows,
        },
        unnamedPlan({ deliveryState })
      );

      render(<DetailsJob jobId="1" />);

      expect(await screen.findByTestId("output-completeness")).toHaveAttribute("data-state", expected);
    });

    it("settles on 'partial' for an unnamed step whose last stored write was a progress snapshot", async () => {
      mockRun(
        {
          structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
          planStructuredOutput: planRows,
        },
        unnamedPlan({ deliveryState: "NOT_DURABLE" })
      );
      const realNow = Date.now.bind(Date);
      let offset = 0;
      const nowSpy = jest.spyOn(Date, "now").mockImplementation(() => realNow() + offset);

      try {
        render(<DetailsJob jobId="1" />);
        await waitFor(() =>
          expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "awaiting-final")
        );
        offset = 61_000;
        await waitFor(
          () => expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "partial"),
          { timeout: 6000 }
        );
      } finally {
        nowSpy.mockRestore();
      }
    }, 12000);

    it("says nothing for an unnamed step that never recorded a delivery outcome", async () => {
      mockRun(
        {
          structuredOutputStatus: { state: "PERSISTED", stepId: "other", phase: "plan", finalSnapshot: true },
          planStructuredOutput: planRows,
        },
        unnamedPlan({ deliveryState: "NOT_DURABLE" })
      );

      render(<DetailsJob jobId="1" />);

      await screen.findByRole("button", { name: /aws_instance\.web/i });
      expect(screen.queryByTestId("output-completeness")).not.toBeInTheDocument();
    });
  });

  it("keeps re-checking until the final output is acknowledged, then stops", async () => {
    let delivered = false;
    let contextCalls = 0;
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        contextCalls += 1;
        return Promise.resolve({
          data: {
            structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: delivered },
            planStructuredOutput: planRows,
          },
        });
      }
      if (url.includes("/step/")) return Promise.resolve({ data: "log line", headers: {} });
      return Promise.resolve(
        jobResponse([
          stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: delivered ? "ACKED" : "NOT_DURABLE" }),
        ])
      );
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() =>
      expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "awaiting-final")
    );

    // Several re-checks that find nothing new must not end the re-checking.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 3500));
    });
    expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "awaiting-final");

    delivered = true; // the executor's delayed final delivery lands

    await waitFor(() => expect(screen.getByTestId("output-completeness")).toHaveAttribute("data-state", "final"), {
      timeout: 9000,
    });

    // Confirmed final: the re-checking has stopped.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1500));
    });
    const callsAfterSettling = contextCalls;
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 3500));
    });
    expect(contextCalls).toBe(callsAfterSettling);
  }, 30000);

  it("does not claim 'no changes' for an empty progress snapshot whose final output has not arrived", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "plan-1", phase: "plan", finalSnapshot: false },
        planStructuredOutput: { "plan-1": [] },
      },
      [stepEntry("plan-1", "Terraform Plan", "1", { deliveryState: "NOT_DURABLE" })]
    );

    render(<DetailsJob jobId="1" />);

    await screen.findByText(/This is not confirmed as "no changes"/);
    expect(
      screen.queryByText("Your infrastructure matches the configuration — no changes needed.")
    ).not.toBeInTheDocument();
  });

  it("shows a run from before delivery was tracked exactly as it was: no completeness notice", async () => {
    mockRun(
      {
        structuredOutputStatus: { state: "PERSISTED", stepId: "apply-1", phase: "apply", finalSnapshot: true },
        planStructuredOutput: planRows,
      },
      [stepEntry("plan-1", "Terraform Plan", "1")]
    );

    render(<DetailsJob jobId="1" />);

    await screen.findByRole("button", { name: /aws_instance\.web/i });
    expect(screen.queryByTestId("output-completeness")).not.toBeInTheDocument();
  });
});

describe("DetailsJob job variables notice", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
  });

  const mockJob = (attributes: Record<string, unknown>) => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: {} });
      }
      return Promise.resolve({ data: { data: { id: "1", attributes }, included: [] } });
    });
  };

  it("lists the names of the job variables a run was created with, and no values", async () => {
    mockJob({ status: "completed", variableNames: ["image_tag", "region"] });
    render(<DetailsJob jobId="1" />);
    const summary = await screen.findByText("2 run variables");
    expect(summary.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("image_tag")).toBeInTheDocument();
    expect(screen.getByText("region")).toBeInTheDocument();
  });

  it("keeps all 100 variable names available within the disclosure", async () => {
    const names = Array.from({ length: 100 }, (_, index) => `service_${index}_deployment_region`);
    mockJob({ status: "completed", variableNames: names });
    render(<DetailsJob jobId="1" />);
    const summary = await screen.findByText("100 run variables");
    expect(summary.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByRole("list", { name: "Run variable names", hidden: true }).children).toHaveLength(100);
    expect(screen.getByText(names[99])).toBeInTheDocument();
  });

  it("uses a singular label for one variable", async () => {
    mockJob({ status: "completed", variableNames: ["region"] });
    render(<DetailsJob jobId="1" />);
    expect(await screen.findByText("1 run variable")).toBeInTheDocument();
  });

  it.each([[undefined], [null], [[]]])("shows nothing when the run has no job variables (%p)", async (names) => {
    mockJob({ status: "completed", variableNames: names });
    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(getMock).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText(/Loading|loading/)).not.toBeInTheDocument());
    expect(screen.queryByText(/run variables?/)).not.toBeInTheDocument();
  });
});
