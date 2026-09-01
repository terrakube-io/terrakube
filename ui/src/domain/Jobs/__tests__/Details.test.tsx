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

describe("DetailsJob SSE reconnect behavior", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReset();
  });

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
