import { render, screen } from "@testing-library/react";
import { StepConsole } from "../StepConsole";

const mockUseStepLog = jest.fn();

jest.mock("../../../hooks", () => ({
  useStepLog: (...args: unknown[]) => mockUseStepLog(...args),
}));

jest.mock("../TerminalOutput", () => ({
  TerminalOutput: ({ outputLog, emptyLabel }: { outputLog: string; emptyLabel?: string }) => (
    <pre data-testid="terminal">{outputLog || emptyLabel}</pre>
  ),
}));

jest.mock("../LiveTerminalOutput", () => ({
  LiveTerminalOutput: () => <div data-testid="live" />,
}));

const item = { id: "s1", name: "Terraform Apply", stepNumber: 1, status: "failed", output: "", outputLog: "" } as never;

const renderConsole = () =>
  render(
    <StepConsole item={item} jobId="j" organizationId="o" structured={{ hasStructuredView: false }} uiType="console" />
  );

beforeEach(() => jest.clearAllMocks());

it("warns that the console output is incomplete when only the archived parts could be read", () => {
  mockUseStepLog.mockReturnValue({
    state: "success",
    text: "Applying...",
    truncated: false,
    incomplete: true,
    retry: jest.fn(),
  });

  renderConsole();

  expect(screen.getByTestId("console-output-incomplete")).toBeInTheDocument();
  expect(screen.getByText("Console output is incomplete")).toBeInTheDocument();
  expect(screen.getByTestId("terminal")).toHaveTextContent("Applying...");
});

it("shows no warning for a complete log", () => {
  mockUseStepLog.mockReturnValue({
    state: "success",
    text: "Apply complete!",
    truncated: false,
    incomplete: false,
    retry: jest.fn(),
  });

  renderConsole();

  expect(screen.queryByTestId("console-output-incomplete")).not.toBeInTheDocument();
});

it("shows no warning while the log is loading or failed to load", () => {
  mockUseStepLog.mockReturnValue({
    state: "error",
    text: "",
    truncated: false,
    incomplete: true,
    retry: jest.fn(),
  });

  renderConsole();

  expect(screen.queryByTestId("console-output-incomplete")).not.toBeInTheDocument();
});

describe("a step that was not executed", () => {
  const notExecutedApply = { ...(item as object), status: "notExecuted" } as never;

  const renderNotExecuted = () =>
    render(
      <StepConsole
        item={notExecutedApply}
        jobId="j"
        organizationId="o"
        structured={{ hasStructuredView: false }}
        uiType="structured"
        contextAvailability="unavailable"
      />
    );

  it("does not warn that structured output is unavailable", () => {
    mockUseStepLog.mockReturnValue({ state: "error", text: "", truncated: false, incomplete: false, retry: jest.fn() });

    renderNotExecuted();

    expect(screen.queryByText("Structured output temporarily unavailable")).not.toBeInTheDocument();
    expect(screen.getByTestId("terminal")).toHaveTextContent("This step was not executed.");
  });

  it("still shows a log when one exists", () => {
    mockUseStepLog.mockReturnValue({
      state: "success",
      text: "partial output",
      truncated: false,
      incomplete: false,
      retry: jest.fn(),
    });

    renderNotExecuted();

    expect(screen.getByTestId("terminal")).toHaveTextContent("partial output");
  });
});
