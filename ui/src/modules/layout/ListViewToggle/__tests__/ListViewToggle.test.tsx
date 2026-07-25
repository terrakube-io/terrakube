import { render, screen, fireEvent } from "@testing-library/react";
import ListViewToggle from "../ListViewToggle";
import * as preference from "../listViewPreference";

describe("ListViewToggle", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.restoreAllMocks();
  });

  it("renders Legacy and New options", () => {
    render(<ListViewToggle value="new" onChange={jest.fn()} />);
    expect(screen.getByText("Legacy")).toBeInTheDocument();
    expect(screen.getByText("New")).toBeInTheDocument();
  });

  it("calls onChange with 'legacy' and persists it when Legacy is clicked", () => {
    const onChange = jest.fn();
    const setSpy = jest.spyOn(preference, "setStoredListViewMode");
    render(<ListViewToggle value="new" onChange={onChange} />);

    fireEvent.click(screen.getByText("Legacy"));

    expect(onChange).toHaveBeenCalledWith("legacy");
    expect(setSpy).toHaveBeenCalledWith("legacy");
  });

  it("calls onChange with 'new' and persists it when New is clicked", () => {
    const onChange = jest.fn();
    const setSpy = jest.spyOn(preference, "setStoredListViewMode");
    render(<ListViewToggle value="legacy" onChange={onChange} />);

    fireEvent.click(screen.getByText("New"));

    expect(onChange).toHaveBeenCalledWith("new");
    expect(setSpy).toHaveBeenCalledWith("new");
  });
});
