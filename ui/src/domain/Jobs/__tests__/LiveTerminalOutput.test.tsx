import { render, screen } from "@testing-library/react";
import { LiveTerminalOutput } from "../LiveTerminalOutput";
import { useLogStream, usePolling } from "../../../hooks";
import { JobStatus, JobStep } from "../../types";

jest.mock("../../../hooks", () => ({
  useLogStream: jest.fn(),
  usePolling: jest.fn(),
}));

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn() },
  axiosClient: { get: jest.fn() },
}));

jest.mock("../TerminalOutput", () => ({
  TerminalOutput: ({
    outputLog,
    truncated,
    emptyLabel,
  }: {
    outputLog: string;
    truncated?: boolean;
    emptyLabel?: string;
  }) => (
    <div data-testid="terminal" data-truncated={String(!!truncated)}>
      {outputLog.length > 0 ? outputLog : (emptyLabel ?? "")}
    </div>
  ),
}));

const makeStep = (overrides: Partial<JobStep>): JobStep => ({
  id: "step-1",
  name: "Plan",
  status: JobStatus.Running,
  stepNumber: 1,
  output: "",
  outputLog: "",
  ...overrides,
});

beforeEach(() => {
  jest.clearAllMocks();
  (usePolling as jest.Mock).mockReturnValue({ clear: jest.fn() });
});

describe("LiveTerminalOutput", () => {
  it("renders the live stream text for a running step", () => {
    (useLogStream as jest.Mock).mockReturnValue({ text: "streamed line" });

    render(<LiveTerminalOutput jobId="1" organizationId="org-1" item={makeStep({ status: JobStatus.Running })} />);

    expect(screen.getByTestId("terminal")).toHaveTextContent("streamed line");
  });

  it("shows a waiting placeholder until the running stream sends something", () => {
    (useLogStream as jest.Mock).mockReturnValue({ text: "" });

    render(
      <LiveTerminalOutput jobId="1" organizationId="org-1" item={makeStep({ status: JobStatus.Running })} />
    );

    expect(screen.getByTestId("terminal")).toHaveTextContent("Waiting for output");
  });

  it("does not connect the stream for a non-running step", () => {
    (useLogStream as jest.Mock).mockReturnValue({ text: "" });

    render(
      <LiveTerminalOutput jobId="1" organizationId="org-1" item={makeStep({ status: JobStatus.Completed })} />
    );

    expect(useLogStream).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }));
  });

  it("caps a very long live stream to a trailing window and marks it truncated", () => {
    const huge = Array.from({ length: 6000 }, (_, i) => `line ${i}`).join("\n");
    (useLogStream as jest.Mock).mockReturnValue({ text: huge });

    render(<LiveTerminalOutput jobId="1" organizationId="org-1" item={makeStep({ status: JobStatus.Running })} />);

    const terminal = screen.getByTestId("terminal");
    expect(terminal).toHaveAttribute("data-truncated", "true");
    expect(terminal.textContent?.startsWith("line 0\n")).toBe(false);
    expect(terminal.textContent).toContain("line 5999");
  });

  it("enables polling only once the SSE stream reports repeated failure", () => {
    let capturedOnStatus: ((s: { failed: boolean }) => void) | undefined;
    (useLogStream as jest.Mock).mockImplementation((opts) => {
      capturedOnStatus = opts.onStatus;
      return { text: "partial" };
    });

    const { rerender } = render(
      <LiveTerminalOutput jobId="1" organizationId="org-1" item={makeStep({ status: JobStatus.Running })} />
    );

    expect((usePolling as jest.Mock).mock.calls.at(-1)?.[1]).toEqual(expect.objectContaining({ enabled: false }));

    capturedOnStatus?.({ failed: true });
    rerender(<LiveTerminalOutput jobId="1" organizationId="org-1" item={makeStep({ status: JobStatus.Running })} />);

    expect((usePolling as jest.Mock).mock.calls.at(-1)?.[1]).toEqual(expect.objectContaining({ enabled: true }));
  });
});
