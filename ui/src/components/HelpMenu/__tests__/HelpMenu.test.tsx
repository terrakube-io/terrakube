import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { HelpMenu } from "../HelpMenu";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

function renderHelpMenu() {
  return render(
    <MemoryRouter>
      <HelpMenu />
    </MemoryRouter>
  );
}

describe("HelpMenu", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
  });

  it("navigates to /api-docs in-app when API Docs is clicked, instead of opening a new tab", async () => {
    renderHelpMenu();

    fireEvent.click(screen.getByLabelText("help menu"));
    fireEvent.click(await screen.findByText("API Docs"));

    expect(mockNavigate).toHaveBeenCalledWith("/api-docs");
  });

  it("still opens Documentation as an external link in a new tab", async () => {
    renderHelpMenu();

    fireEvent.click(screen.getByLabelText("help menu"));

    const docsLink = await screen.findByText("Documentation");
    expect(docsLink.closest("a")).toHaveAttribute("target", "_blank");
  });
});
