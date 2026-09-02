import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { HelpMenu } from "../HelpMenu";

function renderHelpMenu() {
  return render(
    <MemoryRouter>
      <HelpMenu />
    </MemoryRouter>
  );
}

describe("HelpMenu", () => {
  it("links API Docs to /api-docs in-app, instead of opening a new tab", async () => {
    renderHelpMenu();

    fireEvent.click(screen.getByLabelText("help menu"));

    const apiDocsLink = (await screen.findByText("API Docs")).closest("a");
    expect(apiDocsLink).toHaveAttribute("href", "/api-docs");
    expect(apiDocsLink).not.toHaveAttribute("target", "_blank");
  });

  it("still opens Documentation as an external link in a new tab", async () => {
    renderHelpMenu();

    fireEvent.click(screen.getByLabelText("help menu"));

    const docsLink = await screen.findByText("Documentation");
    expect(docsLink.closest("a")).toHaveAttribute("target", "_blank");
  });
});
