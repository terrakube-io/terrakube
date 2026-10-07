import { act, fireEvent, render, screen } from "@testing-library/react";
import JsonViewer, { matchingPaths, resolvePath } from "../JsonViewer";

const state = {
  version: 4,
  outputs: { password_length: { value: 16, sensitive: false } },
  resources: [{ type: "random_password", instances: [{ attributes: { length: 16, special: true } }] }],
};

const filter = (text: string) =>
  act(() => {
    fireEvent.change(screen.getByLabelText("Filter by path or text"), { target: { value: text } });
  });

describe("JsonViewer", () => {
  it("opens the first two levels and summarizes collapsed nodes", () => {
    render(<JsonViewer value={state} label="State" />);
    expect(screen.getByText('"version"')).toBeInTheDocument();
    expect(screen.getByText('"password_length"')).toBeInTheDocument();
    expect(screen.queryByText('"sensitive"')).not.toBeInTheDocument();
    expect(screen.getAllByText("2 keys", { exact: false })).toHaveLength(2);
  });

  it("expands and collapses a node, and all nodes at once", () => {
    render(<JsonViewer value={state} label="State" />);
    const toggle = screen.getByText('"password_length"').closest("button")!;
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(screen.getByText('"sensitive"')).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Expand all" }));
    expect(screen.getByText('"special"')).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Collapse all" }));
    // The root stays open; everything below it collapses.
    expect(screen.getByText('"version"')).toBeInTheDocument();
    expect(screen.queryByText('"password_length"')).not.toBeInTheDocument();
    expect(screen.getByText("1 key", { exact: false })).toBeInTheDocument();
  });

  it("filters to a path", () => {
    render(<JsonViewer value={state} label="State" />);
    filter("resources[0].instances");
    expect(screen.getByText('"resources[0].instances"')).toBeInTheDocument();
    expect(screen.queryByText('"version"')).not.toBeInTheDocument();
    expect(screen.queryByText('"outputs"')).not.toBeInTheDocument();
  });

  it("filters to branches whose keys or values contain the text", () => {
    render(<JsonViewer value={state} label="State" />);
    filter("SPECIAL");
    expect(screen.getByText('"special"')).toBeInTheDocument();
    expect(screen.getByText('"resources"')).toBeInTheDocument();
    expect(screen.queryByText('"outputs"')).not.toBeInTheDocument();
    expect(screen.queryByText('"length"')).not.toBeInTheDocument();

    filter("nothing-like-this");
    expect(screen.getByText('No keys or values contain "nothing-like-this".')).toBeInTheDocument();
  });

  it("shows text that is not JSON as plain text", () => {
    render(<JsonViewer text="not { json" label="Raw" />);
    expect(screen.getByLabelText("Raw").tagName).toBe("PRE");
    expect(screen.getByText("not { json")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Expand all" })).not.toBeInTheDocument();
  });

  it("shows copy feedback inline while in full screen, where toasts are not visible", async () => {
    jest.useFakeTimers();
    const writeText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    let fullscreenElement: Element | null = null;
    Object.defineProperty(document, "fullscreenElement", { configurable: true, get: () => fullscreenElement });
    Object.defineProperty(document, "fullscreenEnabled", { configurable: true, value: true });
    HTMLElement.prototype.requestFullscreen = jest.fn(() => {
      fullscreenElement = document.querySelector(".json-viewer");
      document.dispatchEvent(new Event("fullscreenchange"));
      return Promise.resolve();
    });

    try {
      render(<JsonViewer value={state} label="State" />);
      await act(async () => {
        fireEvent.click(screen.getByText("Full screen"));
      });
      expect(screen.getByText("Exit full screen")).toBeInTheDocument();

      await act(async () => {
        fireEvent.click(screen.getByText("Copy"));
      });
      expect(writeText).toHaveBeenCalled();
      const status = screen.getByText("Copied");
      expect(status).toHaveAttribute("aria-live", "polite");

      act(() => {
        jest.advanceTimersByTime(2000);
      });
      expect(screen.queryByText("Copied")).not.toBeInTheDocument();
    } finally {
      jest.useRealTimers();
      Reflect.deleteProperty(HTMLElement.prototype, "requestFullscreen");
      Reflect.deleteProperty(document, "fullscreenElement");
      Reflect.deleteProperty(document, "fullscreenEnabled");
      Reflect.deleteProperty(navigator, "clipboard");
    }
  });
});

describe("resolvePath and matchingPaths", () => {
  it("resolves dot and bracket paths, and rejects missing ones", () => {
    expect(resolvePath(state, "outputs.password_length.value")).toEqual({ value: 16 });
    expect(resolvePath(state, "resources[0].type")).toEqual({ value: "random_password" });
    expect(resolvePath(state, "resources[1]")).toBeUndefined();
    expect(resolvePath(state, "random password")).toBeUndefined();
  });

  it("collects matches with their ancestors", () => {
    const paths = matchingPaths(state, "random");
    expect([...paths].sort()).toEqual(["", "resources", "resources[0]", "resources[0].type"]);
  });
});
