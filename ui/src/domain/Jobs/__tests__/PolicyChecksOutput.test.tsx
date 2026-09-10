import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import React from "react";
import { PolicyChecksOutput } from "../PolicyChecksOutput";
import { PolicyEvaluationContext } from "../../types";

window._env_ = {
  REACT_APP_AUTHORITY: "http://localhost/authority",
  REACT_APP_CLIENT_ID: "client-id",
  REACT_APP_REDIRECT_URI: "http://localhost/redirect",
  REACT_APP_SCOPE: "openid",
  REACT_APP_TERRAKUBE_API_URL: "http://localhost:8080/api/v1",
  REACT_APP_TERRAKUBE_VERSION: "test",
  REACT_APP_REGISTRY_URI: "http://localhost:8080/registry",
};

const postMock = jest.fn();
const patchMock = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    post: (...args: unknown[]) => postMock(...args),
    patch: (...args: unknown[]) => patchMock(...args),
    get: jest.fn().mockResolvedValue({ data: { data: [] } }),
  },
  axiosRegistry: {
    get: jest.fn().mockResolvedValue({ data: { data: [{ id: "polchk-1" }] } }),
    post: (...args: unknown[]) => postMock(...args),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

describe("PolicyChecksOutput", () => {
  const sampleEvaluation: PolicyEvaluationContext = {
    jobId: "123",
    passedRules: 8,
    warningRules: 1,
    softMandatoryViolations: 1,
    hardMandatoryViolations: 1,
    results: [
      {
        policySetName: "security-baseline",
        enforcementLevel: "hard-mandatory",
        violations: [
          {
            ruleId: "no_public_ssh",
            address: "azurerm_network_security_rule.allow_ssh",
            message: "Inbound SSH open to 0.0.0.0/0",
          },
        ],
      },
      {
        policySetName: "tagging-rules",
        enforcementLevel: "soft-mandatory",
        violations: [
          {
            ruleId: "require_owner_tag",
            address: "azurerm_resource_group.rg",
            message: "Resource group missing owner tag",
          },
        ],
      },
      {
        policySetName: "azure-networking",
        enforcementLevel: "hard-mandatory",
        exemptedViolations: [
          {
            ruleId: "azure_apim_no_public_network",
            address: "azurerm_api_management.partner_gateway",
            ticketReference: "SEC-8842",
            justification: "Approved third-party integration gateway for Partner Corp",
            expiresAt: "2026-12-31T00:00:00.000Z",
          },
        ],
      },
      {
        policySetName: "cost-advisory",
        enforcementLevel: "advisory",
        violations: [
          {
            ruleId: "expensive_vm_size",
            address: "azurerm_linux_virtual_machine.compute",
            message: "Standard_D16s_v5 exceeds recommended dev size",
          },
        ],
      },
    ],
  };

  beforeEach(() => {
    jest.clearAllMocks();
    postMock.mockResolvedValue({ data: { success: true } });
    patchMock.mockResolvedValue({ data: { success: true } });
  });

  it("renders summary badges correctly", () => {
    render(<PolicyChecksOutput policyEvaluation={sampleEvaluation} jobId="123" />);

    expect(screen.getByTestId("pill-passed")).toHaveTextContent("8 Passed");
    expect(screen.getByTestId("pill-exempted")).toHaveTextContent("1 Exempted");
    expect(screen.getByTestId("pill-warning")).toHaveTextContent("1 Warnings");
    expect(screen.getByTestId("pill-soft")).toHaveTextContent("1 Soft Mandatory");
    expect(screen.getByTestId("pill-hard")).toHaveTextContent("1 Hard Mandatory");
  });

  it("renders exemption card with ticket, countdown, and justification", () => {
    render(<PolicyChecksOutput policyEvaluation={sampleEvaluation} jobId="123" />);

    expect(screen.getByText("azure_apim_no_public_network")).toBeInTheDocument();
    expect(screen.getByTestId("exemption-card")).toBeInTheDocument();
    expect(screen.getByText(/Ticket: SEC-8842/)).toBeInTheDocument();
    expect(screen.getByText(/Approved third-party integration gateway for Partner Corp/)).toBeInTheDocument();
  });

  it("filters rules by category", () => {
    render(<PolicyChecksOutput policyEvaluation={sampleEvaluation} jobId="123" />);

    // Click 'Exempted' filter
    const exemptedTab = screen.getByRole("radio", { name: /exempted/i });
    fireEvent.click(exemptedTab);

    expect(screen.getByText("azure_apim_no_public_network")).toBeInTheDocument();
    expect(screen.queryByText("no_public_ssh")).not.toBeInTheDocument();

    // Click 'Violations' filter
    const violationsTab = screen.getByRole("radio", { name: /violations/i });
    fireEvent.click(violationsTab);

    expect(screen.getByText("no_public_ssh")).toBeInTheDocument();
    expect(screen.getByText("require_owner_tag")).toBeInTheDocument();
    expect(screen.queryByText("azure_apim_no_public_network")).not.toBeInTheDocument();
  });

  it("supports deep-link to resource in plan diff", () => {
    const scrollMock = jest.fn();
    const dummyElement = document.createElement("div");
    dummyElement.id = "resource-azurerm_network_security_rule.allow_ssh";
    dummyElement.scrollIntoView = scrollMock;
    document.body.appendChild(dummyElement);

    render(<PolicyChecksOutput policyEvaluation={sampleEvaluation} jobId="123" />);

    const deepLinkBtn = screen.getByTestId("deep-link-no_public_ssh");
    fireEvent.click(deepLinkBtn);

    expect(scrollMock).toHaveBeenCalledWith({ behavior: "smooth", block: "center" });

    document.body.removeChild(dummyElement);
  });

  it("opens override drawer and submits justification", async () => {
    const onOverrideSuccess = jest.fn();
    render(
      <PolicyChecksOutput
        policyEvaluation={sampleEvaluation}
        jobId="123"
        organizationId="org-123"
        onOverrideSuccess={onOverrideSuccess}
      />
    );

    const overrideBtn = screen.getByTestId("override-button");
    fireEvent.click(overrideBtn);

    expect(screen.getByText("Override Soft-Mandatory Policy Checks")).toBeInTheDocument();

    const textarea = screen.getByPlaceholderText(/Approved by SecOps for emergency mitigation/i);
    fireEvent.change(textarea, { target: { value: "Approved hotfix exception" } });

    const submitBtn = screen.getByTestId("submit-override-btn");
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(postMock).toHaveBeenCalledWith(
        "/remote/tfe/v2/policy-checks/polchk-1/actions/override",
        { justification: "Approved hotfix exception" }
      );
      expect(patchMock).toHaveBeenCalledWith(
        "organization/org-123/job/123",
        { data: { type: "job", id: "123", attributes: { status: "approved" } } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      );
      expect(onOverrideSuccess).toHaveBeenCalled();
    });
  });

  it("correctly identifies SOFT_MANDATORY with underscore as soft mandatory violation card", () => {
    const underscoreEvaluation: PolicyEvaluationContext = {
      jobId: "124",
      passedRules: 0,
      warningRules: 0,
      softMandatoryViolations: 1,
      hardMandatoryViolations: 0,
      results: [
        {
          policySetName: "password-policy",
          enforcementLevel: "SOFT_MANDATORY",
          violations: [
            {
              ruleId: "password_length_soft_mandatory",
              address: "random_password.password",
              message: "Password length less than 16",
            },
          ],
        },
      ],
    };

    render(<PolicyChecksOutput policyEvaluation={underscoreEvaluation} jobId="124" />);

    const ruleCard = screen.getByTestId("rule-card");
    expect(ruleCard).toHaveClass("policy-rule-card--soft");
    expect(screen.getByText("Soft Mandatory")).toBeInTheDocument();
    expect(screen.queryByText("Hard Mandatory")).not.toBeInTheDocument();
  });

  it("hides override and reject buttons when status is approved, rejected, or completed", () => {
    const { rerender } = render(
      <PolicyChecksOutput
        policyEvaluation={sampleEvaluation}
        jobId="123"
        status="waitingApproval"
      />
    );

    expect(screen.getByTestId("override-button")).toBeInTheDocument();
    expect(screen.getByTestId("reject-button")).toBeInTheDocument();

    rerender(
      <PolicyChecksOutput
        policyEvaluation={sampleEvaluation}
        jobId="123"
        status="approved"
      />
    );
    expect(screen.queryByTestId("override-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reject-button")).not.toBeInTheDocument();

    rerender(
      <PolicyChecksOutput
        policyEvaluation={sampleEvaluation}
        jobId="123"
        status="rejected"
      />
    );
    expect(screen.queryByTestId("override-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reject-button")).not.toBeInTheDocument();

    rerender(
      <PolicyChecksOutput
        policyEvaluation={sampleEvaluation}
        jobId="123"
        status="completed"
      />
    );
    expect(screen.queryByTestId("override-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reject-button")).not.toBeInTheDocument();
  });

  it("rejects policy override from drawer and marks job as rejected", async () => {
    const onRejectSuccess = jest.fn();
    render(
      <PolicyChecksOutput
        policyEvaluation={sampleEvaluation}
        jobId="123"
        organizationId="org-123"
        status="waitingApproval"
        onRejectSuccess={onRejectSuccess}
      />
    );

    fireEvent.click(screen.getByTestId("override-button"));

    const textarea = screen.getByPlaceholderText(/Approved by SecOps for emergency mitigation/i);
    fireEvent.change(textarea, { target: { value: "Violates security policy - rejected" } });

    const rejectBtn = screen.getByTestId("reject-override-btn");
    fireEvent.click(rejectBtn);

    await waitFor(() => {
      expect(patchMock).toHaveBeenCalledWith(
        "organization/org-123/job/123",
        {
          data: {
            type: "job",
            id: "123",
            attributes: {
              status: "rejected",
              comments: "Violates security policy - rejected",
            },
          },
        },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      );
      expect(onRejectSuccess).toHaveBeenCalled();
    });
  });

  it("does not render a cancel button in the override drawer", () => {
    render(
      <PolicyChecksOutput
        policyEvaluation={sampleEvaluation}
        jobId="123"
        organizationId="org-123"
        status="waitingApproval"
      />
    );

    fireEvent.click(screen.getByTestId("override-button"));
    expect(screen.queryByTestId("cancel-override-btn")).not.toBeInTheDocument();
    expect(screen.queryByText("Cancel")).not.toBeInTheDocument();
  });
});


