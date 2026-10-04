import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import { DateTime } from "luxon";
import { ExemptionRecord, PolicyExemptionTable } from "../PolicyExemptionTable";
import { formatOrdinalDate } from "@/modules/utils/dates";

describe("PolicyExemptionTable", () => {
  const sampleExemptions: ExemptionRecord[] = [
    {
      id: "ex-1",
      ruleId: "aws_s3_no_public_bucket",
      policySetId: "ps-1",
      policySetName: "AWS Security Baseline",
      ticketReference: "SEC-8842",
      justification: "Static website assets bucket approved for public read",
      expiresAt: "2099-12-31T23:59:59Z",
      scopeType: "WORKSPACE",
      workspaceId: "ws-1",
      workspaceName: "frontend-prod",
    },
    {
      id: "ex-2",
      ruleId: "require_cost_center_tag",
      policySetId: "ps-2",
      policySetName: "FinOps Tagging",
      ticketReference: "FIN-1234",
      justification: "Temporary waiver during account migration",
      expiresAt: null, // Permanent
      scopeType: "ORGANIZATION",
    },
    {
      id: "ex-3",
      ruleId: "deny_open_ssh",
      policySetId: "ps-1",
      policySetName: "AWS Security Baseline",
      ticketReference: "SEC-9999",
      justification: "Bastion host emergency access",
      expiresAt: "2020-01-01T00:00:00Z", // Expired
      scopeType: "PROJECT",
      projectId: "proj-1",
      projectName: "Core Infrastructure",
    },
  ];

  it("renders table with records and status badges", () => {
    render(
      <PolicyExemptionTable items={sampleExemptions} managePermission={true} onEdit={jest.fn()} onDelete={jest.fn()} />
    );

    expect(screen.getByText("aws_s3_no_public_bucket")).toBeInTheDocument();
    expect(screen.getByText("require_cost_center_tag")).toBeInTheDocument();
    expect(screen.getByText("deny_open_ssh")).toBeInTheDocument();
    expect(screen.getByText("SEC-8842")).toBeInTheDocument();
    expect(screen.getByText("FIN-1234")).toBeInTheDocument();
    expect(screen.getByText("Permanent")).toBeInTheDocument();
    expect(screen.getByText(/Expired/)).toBeInTheDocument();
    expect(screen.getByText("frontend-prod")).toBeInTheDocument();
    expect(screen.getByText("Organization-wide")).toBeInTheDocument();
    expect(screen.getByText("Core Infrastructure")).toBeInTheDocument();
  });

  it("calls onEdit when edit action is clicked", () => {
    const onEditMock = jest.fn();
    render(
      <PolicyExemptionTable items={sampleExemptions} managePermission={true} onEdit={onEditMock} onDelete={jest.fn()} />
    );

    const editBtn = screen.getByTestId("edit-exemption-ex-1");
    fireEvent.click(editBtn);

    expect(onEditMock).toHaveBeenCalledWith(sampleExemptions[0]);
  });

  it("marks inherited exemptions as read-only in workspace settings", () => {
    render(
      <PolicyExemptionTable
        items={sampleExemptions}
        managePermission={true}
        currentWorkspaceId="ws-1"
        onEdit={jest.fn()}
        onDelete={jest.fn()}
      />
    );

    // ex-1 is for ws-1, so it should have edit action
    expect(screen.getByTestId("edit-exemption-ex-1")).toBeInTheDocument();

    // ex-2 is organization-wide, so it should display Inherited
    const inheritedBadges = screen.getAllByText("Inherited");
    expect(inheritedBadges.length).toBeGreaterThan(0);
  });

  it("renders read-only mode when user lacks managePermission", () => {
    render(
      <PolicyExemptionTable items={sampleExemptions} managePermission={false} onEdit={jest.fn()} onDelete={jest.fn()} />
    );

    const readOnlyLabels = screen.getAllByText("Read-only");
    expect(readOnlyLabels.length).toBe(3);
  });

  it("shows long rule IDs in full, wrapping at underscores, and renders fallback dashes when fields are empty", () => {
    const emptyExemption: ExemptionRecord[] = [
      {
        id: "ex-4",
        ruleId: "long_rule_name_preventing_vertical_character_wrap",
        policySetId: "ps-3",
        policySetName: "",
        ticketReference: "",
        justification: "",
        expiresAt: null,
        scopeType: "ORGANIZATION",
      },
    ];

    render(
      <PolicyExemptionTable items={emptyExemption} managePermission={true} onEdit={jest.fn()} onDelete={jest.fn()} />
    );

    const ruleCode = screen.getByText("long_rule_name_preventing_vertical_character_wrap");
    expect(ruleCode).toBeInTheDocument();
    expect(ruleCode.querySelectorAll("wbr")).toHaveLength(6);

    // Fallback dashes for ticket and justification
    const dashes = screen.getAllByText("—");
    expect(dashes.length).toBeGreaterThanOrEqual(2);
  });

  it("handles numeric timestamp expiresAt without throwing expiresAt.slice is not a function", () => {
    const timestampExemption: ExemptionRecord[] = [
      {
        id: "ex-5",
        ruleId: "password_length_hard_mandatory",
        policySetId: "ps-4",
        policySetName: "Security Standards",
        ticketReference: "SEC-101",
        justification: "Legacy waiver",
        expiresAt: 1924905600000, // Year 2031 timestamp (number)
        scopeType: "WORKSPACE",
      },
    ];

    render(
      <PolicyExemptionTable
        items={timestampExemption}
        managePermission={true}
        onEdit={jest.fn()}
        onDelete={jest.fn()}
      />
    );

    expect(screen.getByText("password_length_hard_mandatory")).toBeInTheDocument();
    const expected = formatOrdinalDate(DateTime.fromMillis(1924905600000));
    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  it("asks for confirmation before revoking an exemption", async () => {
    const onDelete = jest.fn();
    render(
      <PolicyExemptionTable items={sampleExemptions} managePermission={true} onEdit={jest.fn()} onDelete={onDelete} />
    );

    fireEvent.click(screen.getByRole("button", { name: "Revoke exemption aws_s3_no_public_bucket" }));
    expect(onDelete).not.toHaveBeenCalled();

    fireEvent.click(await screen.findByRole("button", { name: "Revoke exemption" }));
    expect(onDelete).toHaveBeenCalledWith(sampleExemptions[0]);
  });

  it("uses sentence-case column headers", () => {
    render(<PolicyExemptionTable items={sampleExemptions} onEdit={jest.fn()} onDelete={jest.fn()} />);

    const headers = screen.getAllByRole("columnheader").map((th) => th.textContent);
    expect(headers).toEqual(["Rule", "Scope", "Reason", "Expires", "Actions"]);
  });
});
