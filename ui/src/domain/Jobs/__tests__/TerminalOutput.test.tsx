import { render, screen, fireEvent } from "@testing-library/react";
import { TerminalOutput } from "../TerminalOutput";
import { __clearAnsiLineCache } from "../ansiChunks";

jest.mock("ansi-to-react", () => {
  return {
    __esModule: true,
    default: ({ children }: { children: string }) => <span data-testid="ansi">{children}</span>,
  };
});

jest.mock("antd", () => {
  const actual = jest.requireActual("antd");
  return {
    ...actual,
    message: { success: jest.fn(), error: jest.fn() },
    Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  };
});

const defaultProps = {
  outputLog: "hello world",
  stepName: "plan",
  isRunning: false,
};

describe("TerminalOutput", () => {
  beforeEach(() => {
    jest.restoreAllMocks();
    __clearAnsiLineCache();
  });

  it("renders output text", () => {
    render(<TerminalOutput {...defaultProps} />);
    expect(screen.getByTestId("ansi")).toHaveTextContent("hello world");
  });

  it("renders all 4 toolbar buttons", () => {
    render(<TerminalOutput {...defaultProps} />);
    expect(screen.getByText("Follow")).toBeInTheDocument();
    expect(screen.getByText("Copy")).toBeInTheDocument();
    expect(screen.getByText("Download")).toBeInTheDocument();
    expect(screen.getByText("Raw")).toBeInTheDocument();
  });

  it("defaults Follow ON", () => {
    render(<TerminalOutput {...defaultProps} />);
    const followBtn = screen.getByText("Follow").closest("button");
    expect(followBtn?.className).toContain("terminal-toolbar-btn--active");
  });

  it("toggles Follow on click", () => {
    render(<TerminalOutput {...defaultProps} />);
    const followBtn = screen.getByText("Follow").closest("button")!;
    expect(followBtn.className).toContain("terminal-toolbar-btn--active");

    fireEvent.click(followBtn);
    expect(followBtn.className).not.toContain("terminal-toolbar-btn--active");

    fireEvent.click(followBtn);
    expect(followBtn.className).toContain("terminal-toolbar-btn--active");
  });

  it("copies stripped text to clipboard", () => {
    const writeText = jest.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });

    render(<TerminalOutput {...defaultProps} outputLog={"[31mred[0m"} />);
    fireEvent.click(screen.getByText("Copy").closest("button")!);

    expect(writeText).toHaveBeenCalledWith("red");
  });

  it("downloads the stripped log via a blob url", () => {
    global.URL.createObjectURL = jest.fn().mockReturnValue("blob:test");
    global.URL.revokeObjectURL = jest.fn();
    const clickSpy = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

    render(<TerminalOutput {...defaultProps} stepName="apply" outputLog={"[32mok[0m"} />);
    fireEvent.click(screen.getByText("Download").closest("button")!);

    expect(global.URL.createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(clickSpy).toHaveBeenCalled();
    expect(global.URL.revokeObjectURL).toHaveBeenCalledWith("blob:test");
  });

  it("shows the empty label when there is no output", () => {
    render(<TerminalOutput {...defaultProps} outputLog="" />);
    expect(screen.getByText("(no output)")).toBeInTheDocument();
  });

  it("shows a truncation notice when truncated", () => {
    render(<TerminalOutput {...defaultProps} truncated />);
    expect(screen.getByText(/Earlier output truncated/)).toBeInTheDocument();
  });

  it("shows a Retry button in the error state and calls onRetry", () => {
    const onRetry = jest.fn();
    render(<TerminalOutput {...defaultProps} error onRetry={onRetry} />);

    fireEvent.click(screen.getByText("Retry").closest("button")!);

    expect(onRetry).toHaveBeenCalled();
    expect(screen.queryByTestId("ansi")).not.toBeInTheDocument();
  });

  it("windows a large log instead of rendering every line", () => {
    const big = Array.from({ length: 5000 }, (_, i) => `line ${i}`).join("\n");
    render(<TerminalOutput {...defaultProps} outputLog={big} />);

    expect(screen.getAllByTestId("ansi").length).toBeLessThan(200);
  });
});
