import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import SavedViews from "../SavedViews";

describe("SavedViews", () => {
  beforeEach(() => localStorage.clear());

  it("cannot save while nothing is filtered", () => {
    render(<SavedViews orgid="org" currentQuery="" onApply={jest.fn()} />);

    expect(screen.getByRole("button", { name: "Save view" })).toBeDisabled();
  });

  const saveAs = async (name: string) => {
    fireEvent.click(screen.getAllByRole("button", { name: "Save view" })[0]);
    fireEvent.change(await screen.findByLabelText("View name"), { target: { value: name } });
    const buttons = screen.getAllByRole("button", { name: "Save view" });
    fireEvent.click(buttons[buttons.length - 1]);
  };

  it("saves the current view and applies it from the menu", async () => {
    const onApply = jest.fn();
    render(<SavedViews orgid="org" currentQuery="chip=attention" onApply={onApply} />);

    await saveAs("Failing");
    fireEvent.click(await screen.findByRole("button", { name: /Saved views/ }));
    fireEvent.click(await screen.findByText("Failing"));

    expect(onApply).toHaveBeenCalledWith("chip=attention");
  });

  it("explains a duplicate name instead of saving it twice", async () => {
    render(<SavedViews orgid="org" currentQuery="chip=attention" onApply={jest.fn()} />);

    await saveAs("Failing");
    await saveAs("failing");

    expect(await screen.findByText("A view with this name exists.")).toBeInTheDocument();
  });

  it("deletes a view without applying it", async () => {
    const onApply = jest.fn();
    render(<SavedViews orgid="org" currentQuery="chip=attention" onApply={onApply} />);

    await saveAs("Failing");
    fireEvent.click(await screen.findByRole("button", { name: "Manage saved views" }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete view Failing" }));

    expect(onApply).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole("button", { name: "Manage saved views" })).toBeDisabled());
  });

  it("shows the views of the organization it is now in, not the one it was in", async () => {
    const { rerender } = render(<SavedViews orgid="a" currentQuery="chip=attention" onApply={jest.fn()} />);
    await saveAs("Failing");

    rerender(<SavedViews orgid="b" currentQuery="chip=attention" onApply={jest.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: /Saved views/ }));
    expect(await screen.findByText("No saved views yet")).toBeInTheDocument();
    expect(screen.queryByText("Failing")).not.toBeInTheDocument();

    rerender(<SavedViews orgid="a" currentQuery="chip=attention" onApply={jest.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: /Saved views/ }));
    expect(await screen.findByText("Failing")).toBeInTheDocument();
  });

  it("limits the name field to the length the store accepts", async () => {
    render(<SavedViews orgid="org" currentQuery="chip=attention" onApply={jest.fn()} />);

    fireEvent.click(screen.getAllByRole("button", { name: "Save view" })[0]);

    expect(await screen.findByLabelText("View name")).toHaveAttribute("maxlength", "40");
  });
});
