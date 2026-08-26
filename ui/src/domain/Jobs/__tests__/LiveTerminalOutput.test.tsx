import { render, screen } from "@testing-library/react";
import { LiveTerminalOutput } from "../LiveTerminalOutput";
import { useLogStream } from "../../../hooks";
import { JobStatus, JobStep } from "../../types";

jest.mock("../../../hooks", () => ({
  useLogStream: jest.fn(),
}));

jest.mock("../TerminalOutput", () => ({
  TerminalOutput: ({ outputLog }: { outputLog: string }) => <div data-testid="terminal">{outputLog}</div>,
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

describe("LiveTerminalOutput", () => {
  it("renders the live stream text for a running step", () => {
    (useLogStream as jest.Mock).mockReturnValue({ text: "streamed line" });

    render(<LiveTerminalOutput jobId="1" organizationId="org-1" item={makeStep({ status: JobStatus.Running })} />);

    expect(screen.getByTestId("terminal")).toHaveTextContent("streamed line");
  });

  it("falls back to the static outputLog while the stream has not sent anything yet", () => {
    (useLogStream as jest.Mock).mockReturnValue({ text: "" });

    render(
      <LiveTerminalOutput
        jobId="1"
        organizationId="org-1"
        item={makeStep({ status: JobStatus.Running, outputLog: "Initializing the backend..." })}
      />
    );

    expect(screen.getByTestId("terminal")).toHaveTextContent("Initializing the backend...");
  });

  it("renders the static outputLog for a completed step without connecting the stream", () => {
    (useLogStream as jest.Mock).mockReturnValue({ text: "" });

    render(
      <LiveTerminalOutput
        jobId="1"
        organizationId="org-1"
        item={makeStep({ status: JobStatus.Completed, output: "some-url", outputLog: "final output" })}
      />
    );

    expect(screen.getByTestId("terminal")).toHaveTextContent("final output");
    expect(useLogStream).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }));
  });
});
