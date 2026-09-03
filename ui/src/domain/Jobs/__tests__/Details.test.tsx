import { render, screen, waitFor } from "@testing-library/react";
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

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => getMock(...args) },
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
          included: [
            { id: "step-2", type: "step", attributes: { name: "Apply", status: jobStatus, stepNumber: "2" } },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(screen.getByText(/Apply/)).toBeInTheDocument());

    jobStatus = "completed";
    contextPersisted = true;

    await waitFor(
      () => expect(screen.getByRole("button", { name: /aws_instance\.reconciled/i })).toBeInTheDocument(),
      { timeout: 8000 }
    );
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
            { id: "step-1", type: "step", attributes: { name: "Terraform Plan", status: "completed", stepNumber: "1" } },
            { id: "step-2", type: "step", attributes: { name: "Terraform Apply", status: "completed", stepNumber: "2" } },
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
          included: [
            { id: "step-1", type: "step", attributes: { name: "Plan", status: jobStatus, stepNumber: "1" } },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(screen.getByText(/Plan/)).toBeInTheDocument());

    jobStatus = "completed";

    await waitFor(
      () => expect(screen.getByText("Structured output temporarily unavailable")).toBeInTheDocument(),
      { timeout: 8000 }
    );
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
          included: [
            { id: "step-2", type: "step", attributes: { name: "Apply", status: "running", stepNumber: "2" } },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);
    await waitFor(() => expect(screen.getByText(/Apply/)).toBeInTheDocument());

    contextHasData = true;

    // No SSE event ever arrives; the 5s refreshJobDetails HTTP poll must still pick up the diff.
    await waitFor(
      () => expect(screen.getByRole("button", { name: /aws_instance\.via_poll/i })).toBeInTheDocument(),
      { timeout: 8000 }
    );
    expect(screen.queryByText("Loading Job...")).not.toBeInTheDocument();
  }, 12000);

  it(
    "keeps the last known structured output when the job leaves running and the live channel disables",
    async () => {
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
          expect(screen.getByText("Completed")).toBeInTheDocument();
        },
        { timeout: 8000 }
      );

      expect(screen.getByRole("button", { name: /aws_instance\.live/i })).toBeInTheDocument();
    },
    10000
  );
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
      if (url.includes("/step/")) return Promise.resolve({ data: "Apply complete! Resources: 0 added, 0 changed, 0 destroyed.", headers: {} });
      return Promise.resolve(twoStepJob("completed"));
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => expect(screen.getByText(/Apply completed with no changes/)).toBeInTheDocument());
    expect(
      screen.getByText("Your infrastructure matches the configuration — no changes needed.")
    ).toBeInTheDocument();
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
      if (url.includes("/step/")) return Promise.resolve({ data: "Apply complete! Resources: 0 added, 0 changed, 0 destroyed.", headers: {} });
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
              "plan-1": [
                { address: "aws_instance.x", action: "create", actions: ["create"], after: { id: "i-1" } },
              ],
            },
          },
        });
      }
      if (url.includes("/step/")) return Promise.resolve({ data: "Terraform will perform the following actions:", headers: {} });
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

    await waitFor(() => expect(screen.getAllByText("Structured output temporarily unavailable").length).toBeGreaterThan(0));
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
            { id: "plan-1", type: "step", attributes: { name: "Terraform Plan", status: "completed", stepNumber: "1" } },
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
