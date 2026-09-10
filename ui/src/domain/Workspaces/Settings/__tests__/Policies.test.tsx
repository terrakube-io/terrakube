import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { WorkspacePolicies } from "../Policies";
import axiosInstance from "../../../../config/axiosConfig";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

jest.mock("../../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: jest.fn().mockResolvedValue({ data: { data: [] } }),
    post: jest.fn(),
    delete: jest.fn(),
    patch: jest.fn(),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

describe("WorkspacePolicies", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  const baseWorkspace = {
    id: "ws-123",
    attributes: {
      name: "prod-cluster",
      locked: false,
      lastJobStatus: "completed",
      policyComplianceStatus: "COMPLIANT",
    },
    relationships: {
      organization: { data: { id: "org-456" } },
    },
  } as any;

  it("renders COMPLIANT status badge and allows triggering evaluation", async () => {
    (axiosInstance.post as jest.Mock).mockResolvedValueOnce({
      data: { jobId: "999", status: "pending" },
    });

    render(<WorkspacePolicies workspace={baseWorkspace} manageWorkspace={true} />);

    expect(screen.getByText("COMPLIANT")).toBeInTheDocument();
    expect(screen.getByText("Evaluate Policies Now")).toBeEnabled();

    fireEvent.click(screen.getByText("Evaluate Policies Now"));

    await waitFor(() => {
      expect(axiosInstance.post).toHaveBeenCalledWith(
        "https://terrakube-api.test/policy/v1/organization/org-456/workspace/ws-123/evaluation"
      );
      expect(mockNavigate).toHaveBeenCalledWith(
        "/organizations/org-456/workspaces/ws-123/runs/999"
      );
    });
  });

  it("renders NON-COMPLIANT status badge when status is NON_COMPLIANT", () => {
    const ws = {
      ...baseWorkspace,
      attributes: {
        ...baseWorkspace.attributes,
        policyComplianceStatus: "NON_COMPLIANT",
      },
    };

    render(<WorkspacePolicies workspace={ws} manageWorkspace={true} />);
    expect(screen.getByText("NON-COMPLIANT")).toBeInTheDocument();
  });

  it("disables button and displays warning alert when workspace is locked", () => {
    const ws = {
      ...baseWorkspace,
      attributes: {
        ...baseWorkspace.attributes,
        locked: true,
      },
    };

    render(<WorkspacePolicies workspace={ws} manageWorkspace={true} />);
    expect(screen.getByText("Workspace Locked")).toBeInTheDocument();
    expect(screen.getByText("Evaluate Policies Now").closest("button")).toBeDisabled();
  });

  it("disables button and displays warning alert when workspace has no completed runs", () => {
    const ws = {
      ...baseWorkspace,
      attributes: {
        ...baseWorkspace.attributes,
        lastJobStatus: "NeverExecuted",
      },
    };

    render(<WorkspacePolicies workspace={ws} manageWorkspace={true} />);
    expect(screen.getByText("No Completed Runs")).toBeInTheDocument();
    expect(screen.getByText("Evaluate Policies Now").closest("button")).toBeDisabled();
  });

  it("disables button when user lacks both manageWorkspace and planJob permissions", () => {
    render(<WorkspacePolicies workspace={baseWorkspace} manageWorkspace={false} planJob={false} />);
    expect(screen.getByText("Evaluate Policies Now").closest("button")).toBeDisabled();
  });

  it("enables button when user has planJob permission even without manageWorkspace", () => {
    render(<WorkspacePolicies workspace={baseWorkspace} manageWorkspace={false} planJob={true} />);
    expect(screen.getByText("Evaluate Policies Now")).toBeEnabled();
  });
});
