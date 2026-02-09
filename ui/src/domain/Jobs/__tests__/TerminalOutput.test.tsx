import { render, screen, fireEvent } from "@testing-library/react";
import { TerminalOutput } from "../TerminalOutput";

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

  it("defaults Follow OFF when not running", () => {
    render(<TerminalOutput {...defaultProps} isRunning={false} />);
    const followBtn = screen.getByText("Follow").closest("button");
    expect(followBtn?.className).not.toContain("terminal-toolbar-btn--active");
  });

  it("defaults Follow ON when running", () => {
    render(<TerminalOutput {...defaultProps} isRunning={true} />);
    const followBtn = screen.getByText("Follow").closest("button");
    expect(followBtn?.className).toContain("terminal-toolbar-btn--active");
  });

  it("toggles Follow on click", () => {
    render(<TerminalOutput {...defaultProps} isRunning={false} />);
    const followBtn = screen.getByText("Follow").closest("button")!;
    expect(followBtn.className).not.toContain("terminal-toolbar-btn--active");

    fireEvent.click(followBtn);
    expect(followBtn.className).toContain("terminal-toolbar-btn--active");

    fireEvent.click(followBtn);
    expect(followBtn.className).not.toContain("terminal-toolbar-btn--active");
  });

  it("copies stripped text to clipboard", async () => {
    const writeText = jest.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });

    render(<TerminalOutput {...defaultProps} outputLog={"\u001b[31mred\u001b[0m"} />);
    fireEvent.click(screen.getByText("Copy").closest("button")!);

    expect(writeText).toHaveBeenCalledWith("red");
  });

  it("shows error when clipboard write fails", async () => {
    const writeText = jest.fn().mockRejectedValue(new Error("denied"));
    Object.assign(navigator, { clipboard: { writeText } });
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { message: mockMsg } = require("antd");

    render(<TerminalOutput {...defaultProps} />);
    fireEvent.click(screen.getByText("Copy").closest("button")!);

    await new Promise((r) => setTimeout(r, 0));
    expect(mockMsg.error).toHaveBeenCalledWith("Failed to copy to clipboard");
  });

  it("downloads stripped text as .log file with correct filename", () => {
    const createObjectURL = jest.fn().mockReturnValue("blob:test");
    const revokeObjectURL = jest.fn();
    global.URL.createObjectURL = createObjectURL;
    global.URL.revokeObjectURL = revokeObjectURL;

    const fakeAnchor = { click: jest.fn(), href: "", download: "" } as unknown as HTMLAnchorElement;
    const originalAppend = document.body.appendChild.bind(document.body);
    const originalRemove = document.body.removeChild.bind(document.body);
    const appendSpy = jest.spyOn(document.body, "appendChild").mockImplementation(<T extends Node>(node: T): T => {
      if (node === (fakeAnchor as unknown as Node)) return node;
      return originalAppend(node);
    });
    const removeSpy = jest.spyOn(document.body, "removeChild").mockImplementation(<T extends Node>(node: T): T => {
      if (node === (fakeAnchor as unknown as Node)) return node;
      return originalRemove(node);
    });
    const originalCreateElement = document.createElement.bind(document);
    jest.spyOn(document, "createElement").mockImplementation((tag: string, options?: ElementCreationOptions) => {
      if (tag === "a") return fakeAnchor;
      return originalCreateElement(tag, options);
    });

    render(<TerminalOutput {...defaultProps} stepName="apply" outputLog={"\u001b[32mok\u001b[0m"} />);
    fireEvent.click(screen.getByText("Download").closest("button")!);

    expect(createObjectURL).toHaveBeenCalled();
    expect(fakeAnchor.download).toBe("apply.log");
    expect(appendSpy).toHaveBeenCalledWith(fakeAnchor);
    expect((fakeAnchor as unknown as { click: jest.Mock }).click).toHaveBeenCalled();
    expect(removeSpy).toHaveBeenCalledWith(fakeAnchor);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:test");
  });

  it("opens raw text in new tab and revokes blob URL", () => {
    const createObjectURL = jest.fn().mockReturnValue("blob:raw");
    const revokeObjectURL = jest.fn();
    global.URL.createObjectURL = createObjectURL;
    global.URL.revokeObjectURL = revokeObjectURL;
    const openSpy = jest.spyOn(window, "open").mockImplementation(() => null);

    render(<TerminalOutput {...defaultProps} />);
    fireEvent.click(screen.getByText("Raw").closest("button")!);

    expect(openSpy).toHaveBeenCalledWith("blob:raw", "_blank");
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:raw");
  });
});
