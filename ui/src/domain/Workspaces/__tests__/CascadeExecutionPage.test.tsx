import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "@/config/actionTypes";
import { CascadeExecutionPage } from "../CascadeExecutionPage";

const getMock = jest.fn();

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => getMock(...args) },
}));

jest.mock("../CascadeGraph", () => ({
  CascadeGraph: ({ cascadeId, organizationId }: { cascadeId: string; organizationId: string }) => (
    <div>
      graph for {cascadeId} in {organizationId}
    </div>
  ),
}));

// PageWrapper deep-imports an ESM-only antd submodule Jest can't transform, so pages built on it mock it.
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: function PageWrapper({
    title,
    subTitle,
    breadcrumbs,
    children,
  }: {
    title: string;
    subTitle?: string;
    breadcrumbs?: { label: string; path?: string }[];
    children?: React.ReactNode;
  }) {
    return (
      <div>
        <h2>{title}</h2>
        <p>{subTitle}</p>
        <nav>
          {breadcrumbs?.map((crumb) =>
            crumb.path ? (
              <a key={crumb.label} href={crumb.path}>
                {crumb.label}
              </a>
            ) : (
              <span key={crumb.label}>{crumb.label}</span>
            )
          )}
        </nav>
        {children}
      </div>
    );
  },
}));

const renderPage = (path = "/workspaces/ws-1/cascades/cascade-1") =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/workspaces/:id/cascades/:cascadeId" element={<CascadeExecutionPage />} />
        <Route path="/organizations/:orgid/workspaces/:id/cascades/:cascadeId" element={<CascadeExecutionPage />} />
      </Routes>
    </MemoryRouter>
  );

describe("CascadeExecutionPage", () => {
  beforeEach(() => {
    sessionStorage.clear();
    getMock.mockReset();
    getMock.mockResolvedValue({ data: { data: { attributes: { name: "network" } } } });
  });

  it("reads the workspace and cascade ids from the route, not props", async () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-1");
    renderPage();

    expect(await screen.findByText("graph for cascade-1 in org-1")).toBeInTheDocument();
  });

  it("prefers the organization in the route over the stored one", async () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "stale-org");
    renderPage("/organizations/org-2/workspaces/ws-1/cascades/cascade-1");

    expect(await screen.findByText("graph for cascade-1 in org-2")).toBeInTheDocument();
  });

  it("uses the standard page frame: title, subtitle and breadcrumbs back to the cascade lists", async () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-1");
    sessionStorage.setItem(ORGANIZATION_NAME, "acme");
    renderPage();

    expect(await screen.findByRole("heading", { name: "Run cascade" })).toBeInTheDocument();
    expect(await screen.findByText(/Started from network\./)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "acme" })).toHaveAttribute("href", "/organizations/org-1/workspaces");
    expect(screen.getByRole("link", { name: "Run cascades" })).toHaveAttribute("href", "/organizations/org-1/cascades");
    expect(screen.getByRole("link", { name: "network" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ws-1/cascades"
    );
  });

  it("still renders when the workspace name cannot be loaded", async () => {
    getMock.mockRejectedValue(new Error("nope"));
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-1");
    renderPage();

    expect(await screen.findByText("graph for cascade-1 in org-1")).toBeInTheDocument();
    expect(screen.getByText(/Started from Workspace\./)).toBeInTheDocument();
  });
});
