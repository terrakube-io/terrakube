import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";
import { CascadeExecutionPage } from "../CascadeExecutionPage";

jest.mock("../CascadeGraph", () => ({
  CascadeGraph: ({ cascadeId, organizationId }: { cascadeId: string; organizationId: string }) => (
    <div>
      graph for {cascadeId} in {organizationId}
    </div>
  ),
}));

const renderPage = (path = "/workspaces/ws-1/cascades/cascade-1") =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/workspaces/:id/cascades/:cascadeId" element={<CascadeExecutionPage />} />
      </Routes>
    </MemoryRouter>
  );

describe("CascadeExecutionPage", () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it("reads the workspace and cascade ids from the route, not props", async () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-1");
    renderPage();

    expect(await screen.findByText("graph for cascade-1 in org-1")).toBeInTheDocument();
  });

  it("links back to the workspace's cascade list", async () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-1");
    renderPage();

    const backLink = await screen.findByRole("link", { name: /back to cascades/i });
    expect(backLink).toHaveAttribute("href", "/organizations/org-1/workspaces/ws-1/cascades");
  });
});
