import { fireEvent, render, screen } from "@testing-library/react";
import { createRef } from "react";
import DisabledReason from "../DisabledReason";
import { useSlashToFocus } from "../useSlashToFocus";

describe("DisabledReason", () => {
  it("leaves an enabled control untouched", () => {
    render(
      <DisabledReason disabled={false} reason="You need something.">
        <button type="button">Edit</button>
      </DisabledReason>
    );

    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(screen.queryByLabelText("You need something.")).not.toBeInTheDocument();
  });

  it("gives a disabled control a reason that keyboard and screen reader users can reach", () => {
    render(
      <DisabledReason disabled reason="You need Manage Workspaces to create projects.">
        <button type="button" disabled>
          New project
        </button>
      </DisabledReason>
    );

    const wrapper = screen.getByLabelText("You need Manage Workspaces to create projects.");
    expect(wrapper).toHaveAttribute("tabindex", "0");
    expect(wrapper).toContainElement(screen.getByRole("button", { name: "New project" }));
  });
});

function Harness() {
  const ref = createRef<HTMLInputElement>();
  useSlashToFocus(ref);
  return (
    <>
      <input ref={ref} aria-label="search" />
      <input aria-label="other" />
      <button type="button">button</button>
    </>
  );
}

describe("useSlashToFocus", () => {
  it("focuses the search when / is pressed outside a text field", () => {
    render(<Harness />);

    fireEvent.keyDown(document.body, { key: "/" });

    expect(screen.getByLabelText("search")).toHaveFocus();
  });

  it("does not steal the key while typing in another field", () => {
    render(<Harness />);
    const other = screen.getByLabelText("other");
    other.focus();

    fireEvent.keyDown(other, { key: "/" });

    expect(other).toHaveFocus();
  });

  it("ignores / with a modifier, and other keys", () => {
    render(<Harness />);

    fireEvent.keyDown(document.body, { key: "/", ctrlKey: true });
    fireEvent.keyDown(document.body, { key: "a" });

    expect(screen.getByLabelText("search")).not.toHaveFocus();
  });
});
