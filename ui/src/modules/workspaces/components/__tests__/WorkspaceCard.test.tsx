import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import WorkspaceCard from "../WorkspaceCard";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { JobStatus } from "@/domain/types";

describe("WorkspaceCard", () => {
  const baseItem: WorkspaceListItem = {
    id: "ws-1",
    name: "test-workspace",
    description: "A test workspace",
    iacType: "terraform",
    source: "https://github.com/acme/infra",
    normalizedSource: "https://github.com/acme/infra",
    lastStatus: JobStatus.Completed,
    lastRun: "2024-06-01T00:00:00.000Z",
    terraformVersion: "1.8.0",
    policyComplianceStatus: "COMPLIANT",
  };

  it("renders workspace details and policy compliance badge", () => {
    render(
      <MemoryRouter>
        <WorkspaceCard item={baseItem} tags={[]} organizationId="org-123" />
      </MemoryRouter>
    );

    expect(screen.getByText("test-workspace")).toBeInTheDocument();
    expect(screen.getByText("Compliant")).toBeInTheDocument();

    const policyLink = screen.getByRole("link", { name: /Policy compliance: Compliant/i });
    expect(policyLink).toHaveAttribute("href", "/organizations/org-123/workspaces/ws-1/settings/policies");
  });

  it("renders UNKNOWN policy badge when policyComplianceStatus is not set", () => {
    const itemWithoutPolicy: WorkspaceListItem = {
      ...baseItem,
      policyComplianceStatus: undefined,
    };

    render(
      <MemoryRouter>
        <WorkspaceCard item={itemWithoutPolicy} tags={[]} organizationId="org-123" />
      </MemoryRouter>
    );

    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });
});
