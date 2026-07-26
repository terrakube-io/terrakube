import { render, screen, fireEvent } from "@testing-library/react";
import ListViewToggle from "../ListViewToggle";
import * as preference from "../listViewPreference";

describe("ListViewToggle", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.restoreAllMocks();
  });

  it("renders Cards and Compact options", () => {
    render(<ListViewToggle value="compact" onChange={jest.fn()} />);
    expect(screen.getByText("Cards")).toBeInTheDocument();
    expect(screen.getByText("Compact")).toBeInTheDocument();
  });

  it("calls onChange with 'cards' and persists it when Cards is clicked", () => {
    const onChange = jest.fn();
    const setSpy = jest.spyOn(preference, "setStoredListViewMode");
    render(<ListViewToggle value="compact" onChange={onChange} />);

    fireEvent.click(screen.getByText("Cards"));

    expect(onChange).toHaveBeenCalledWith("cards");
    expect(setSpy).toHaveBeenCalledWith("cards");
  });

  it("calls onChange with 'compact' and persists it when Compact is clicked", () => {
    const onChange = jest.fn();
    const setSpy = jest.spyOn(preference, "setStoredListViewMode");
    render(<ListViewToggle value="cards" onChange={onChange} />);

    fireEvent.click(screen.getByText("Compact"));

    expect(onChange).toHaveBeenCalledWith("compact");
    expect(setSpy).toHaveBeenCalledWith("compact");
  });
});
