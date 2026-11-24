import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import projectService from "../projectService";
import ProjectPoliciesTab from "../ProjectPoliciesTab";

jest.mock("../projectService", () => ({ __esModule: true, default: { listProjectPolicySets: jest.fn() } }));

const list = projectService.listProjectPolicySets as jest.Mock;

const renderTab = () =>
  render(
    <MemoryRouter>
      <ProjectPoliciesTab orgid="org" projectId="p1" />
    </MemoryRouter>
  );

describe("ProjectPoliciesTab", () => {
  it("shows where each policy set comes from", async () => {
    list.mockResolvedValue({
      isError: false,
      data: [
        { id: "a", name: "Baseline", enforcementLevel: "HARD_MANDATORY", global: true, attachedToProject: false },
        { id: "b", name: "Payments", enforcementLevel: "ADVISORY", global: false, attachedToProject: true },
      ],
    });

    renderTab();

    expect(await screen.findByText("Baseline")).toBeInTheDocument();
    expect(screen.getByText("Global, every workspace")).toBeInTheDocument();
    expect(screen.getByText("Attached to this project")).toBeInTheDocument();
    expect(screen.getByText("Advisory")).toBeInTheDocument();
  });

  it("explains an empty list", async () => {
    list.mockResolvedValue({ isError: false, data: [] });

    renderTab();

    expect(await screen.findByText("No policy sets apply to this whole project.")).toBeInTheDocument();
  });

  it("warns when the policy sets cannot be read", async () => {
    list.mockResolvedValue({ isError: true, data: [] });

    renderTab();

    expect(await screen.findByText(/may not have permission/)).toBeInTheDocument();
  });
});
