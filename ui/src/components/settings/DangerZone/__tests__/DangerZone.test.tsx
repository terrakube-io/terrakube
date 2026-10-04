import { fireEvent, render, screen, within } from "@testing-library/react";
import DangerZone from "../DangerZone";

function renderZone(onConfirm = jest.fn()) {
  render(
    <DangerZone
      actionName="Delete this workspace"
      description="The workspace will be deleted."
      confirmValue="my-workspace"
      confirmMessage="This cannot be undone."
      onConfirm={onConfirm}
    />
  );
  return onConfirm;
}

describe("DangerZone", () => {
  it("renders the section title and the action name", () => {
    renderZone();
    expect(screen.getByRole("heading", { name: "Destruction and deletion" })).toBeInTheDocument();
    expect(screen.getByText("The workspace will be deleted.")).toBeInTheDocument();
  });

  it("confirms by typing the name before calling onConfirm", () => {
    const onConfirm = renderZone();
    fireEvent.click(screen.getByRole("button", { name: "Delete this workspace" }));

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("This cannot be undone.");
    const confirm = within(dialog).getByRole("button", { name: "Delete this workspace" });
    expect(confirm).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Type the name to confirm"), { target: { value: "my-workspace" } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});
