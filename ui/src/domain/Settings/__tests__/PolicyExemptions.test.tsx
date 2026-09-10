import React from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { PolicyExemptionsSettings } from "../PolicyExemptions";
import axiosInstance from "../../../config/axiosConfig";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: jest.fn(),
    delete: jest.fn(),
    post: jest.fn(),
    patch: jest.fn(),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

describe("PolicyExemptionsSettings", () => {
  const sampleRawExemptions = [
    {
      id: "ex-1",
      attributes: {
        ruleId: "aws_s3_bucket_no_public",
        ticketReference: "SEC-101",
        justification: "Approved CDN bucket waiver",
        expiresAt: "2099-12-31T00:00:00Z",
      },
      relationships: {
        organization: { data: { id: "org-1" } },
        policySet: { data: { id: "ps-1" } },
        workspace: { data: { id: "ws-1" } },
      },
    },
    {
      id: "ex-2",
      attributes: {
        ruleId: "require_owner_tag",
        ticketReference: "SEC-202",
        justification: "Global tagging exemption for sandbox",
        expiresAt: null,
      },
      relationships: {
        organization: { data: { id: "org-1" } },
        policySet: { data: { id: "ps-2" } },
      },
    },
  ];

  const sampleIncluded = [
    {
      type: "policy_set",
      id: "ps-1",
      attributes: { name: "AWS Security Rules" },
    },
    {
      type: "policy_set",
      id: "ps-2",
      attributes: { name: "FinOps Governance" },
    },
    {
      type: "workspace",
      id: "ws-1",
      attributes: { name: "frontend-prod" },
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
    (axiosInstance.get as jest.Mock).mockImplementation((url: string) => {
      if (url === "policy_set") {
        return Promise.resolve({
          data: {
            data: [
              { id: "ps-1", attributes: { name: "AWS Security Rules" } },
              { id: "ps-2", attributes: { name: "FinOps Governance" } },
            ],
          },
        });
      }
      if (url.includes("policyExemption")) {
        return Promise.resolve({
          data: {
            data: sampleRawExemptions,
            included: sampleIncluded,
          },
        });
      }
      return Promise.resolve({ data: { data: [] } });
    });
  });

  it("renders list of exemptions with filters", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicyExemptionsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("aws_s3_bucket_no_public")).toBeInTheDocument();
      expect(screen.getByText("require_owner_tag")).toBeInTheDocument();
      expect(screen.getByText("SEC-101")).toBeInTheDocument();
      expect(screen.getByText("SEC-202")).toBeInTheDocument();
      expect(screen.getByText("AWS Security Rules")).toBeInTheDocument();
      expect(screen.getByText("FinOps Governance")).toBeInTheDocument();
      expect(screen.getByText("Workspace: frontend-prod")).toBeInTheDocument();
      expect(screen.getByText("Organization-Wide")).toBeInTheDocument();
    });

    expect(screen.getByTestId("add-exemption-btn")).toBeInTheDocument();
  });

  it("opens create exemption modal on button click", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicyExemptionsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByTestId("add-exemption-btn")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("add-exemption-btn"));

    await waitFor(() => {
      expect(screen.getByText("Create Policy Exemption")).toBeInTheDocument();
    });
  });
});
