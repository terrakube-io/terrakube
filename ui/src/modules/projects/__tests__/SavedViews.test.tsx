import { fireEvent, render, screen } from "@testing-library/react";
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
    fireEvent.click(await screen.findByRole("button", { name: /Saved views/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete view Failing" }));

    expect(onApply).not.toHaveBeenCalled();
    expect(await screen.findByText("No saved views yet")).toBeInTheDocument();
  });
});
