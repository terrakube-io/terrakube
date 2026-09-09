import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import React from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { PolicySetsSettings } from "../PolicySets";

window._env_ = {
  REACT_APP_AUTHORITY: "http://localhost/authority",
  REACT_APP_CLIENT_ID: "client-id",
  REACT_APP_REDIRECT_URI: "http://localhost/redirect",
  REACT_APP_SCOPE: "openid",
  REACT_APP_TERRAKUBE_API_URL: "http://localhost:8080/api/v1",
  REACT_APP_TERRAKUBE_VERSION: "test",
  REACT_APP_REGISTRY_URI: "http://localhost:8080/registry",
};

const getMock = jest.fn();
const deleteMock = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => getMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
    post: jest.fn().mockResolvedValue({ data: { data: { id: "new-id" } } }),
    patch: jest.fn().mockResolvedValue({ data: { data: { id: "edit-id" } } }),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

describe("PolicySetsSettings", () => {
  const samplePolicySets = [
    {
      id: "ps-1",
      attributes: {
        name: "security-baseline",
        description: "Enforces enterprise encryption and access control",
        enforcementLevel: "HARD_MANDATORY",
        global: true,
        repository: "https://github.com/org/opa-policies",
        branch: "main",
        folder: "/baseline",
      },
      relationships: {
        organization: { data: { id: "org-1" } },
        attachments: { data: [] },
      },
    },
    {
      id: "ps-2",
      attributes: {
        name: "tagging-rules",
        description: "Requires cost center and env tags",
        enforcementLevel: "SOFT_MANDATORY",
        shadowEnforcementLevel: "HARD_MANDATORY",
        global: false,
        overrideTeam: "secops",
        repository: "https://github.com/org/tag-policies",
        branch: "main",
      },
      relationships: {
        organization: { data: { id: "org-1" } },
        attachments: { data: [{ id: "att-1" }, { id: "att-2" }] },
      },
    },
    {
      id: "ps-3",
      attributes: {
        name: "advisory-checks",
        description: "Advises on deprecated cloud resource types",
        enforcementLevel: "ADVISORY",
        global: false,
        repository: "https://github.com/org/advisory-policies",
        branch: "develop",
      },
      relationships: {
        organization: { data: { id: "org-1" } },
        attachments: { data: [{ id: "att-3" }] },
      },
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    getMock.mockImplementation((url: string) => {
      if (url.includes("policy_set")) {
        return Promise.resolve({ data: { data: samplePolicySets } });
      }
      return Promise.resolve({ data: { data: [] } });
    });
  });

  it("renders list of policy sets in card mode with filters and view toggle", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicySetsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("security-baseline")).toBeInTheDocument();
      expect(screen.getByText("tagging-rules")).toBeInTheDocument();
      expect(screen.getByText("advisory-checks")).toBeInTheDocument();
      expect(screen.getByText("Hard Mandatory")).toBeInTheDocument();
      expect(screen.getByText("Soft Mandatory")).toBeInTheDocument();
      expect(screen.getByText("Advisory")).toBeInTheDocument();
      expect(screen.getByText("Global")).toBeInTheDocument();
      expect(screen.getByText("2 Attachments")).toBeInTheDocument();
      expect(screen.getByText("1 Attachment")).toBeInTheDocument();
      expect(screen.getByText("Override Team: secops")).toBeInTheDocument();
    });

    // Check filter elements are present
    expect(screen.getByTestId("policy-set-search-input")).toBeInTheDocument();
    expect(screen.getByTestId("policy-set-category-select")).toBeInTheDocument();
    expect(screen.getByTestId("policy-set-scope-select")).toBeInTheDocument();
    expect(screen.getByTestId("policy-set-view-toggle")).toBeInTheDocument();
  });

  it("allows toggling between Card mode and Compact mode", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicySetsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByTestId("policy-set-card-ps-1")).toBeInTheDocument();
    });

    // Toggle to compact mode
    const compactSegment = screen.getByRole("radio", { name: /compact/i });
    fireEvent.click(compactSegment);

    // Table view should now be displayed
    await waitFor(() => {
      expect(screen.getByTestId("policy-sets-compact-table")).toBeInTheDocument();
      expect(screen.getByTestId("policy-set-table-link-ps-1")).toBeInTheDocument();
    });

    // Preference should be persisted in localStorage
    expect(localStorage.getItem("terrakube.policySets.listViewMode")).toBe("compact");

    // Toggle back to cards
    const cardsSegment = screen.getByRole("radio", { name: /cards/i });
    fireEvent.click(cardsSegment);

    await waitFor(() => {
      expect(screen.getByTestId("policy-set-card-ps-1")).toBeInTheDocument();
    });
    expect(localStorage.getItem("terrakube.policySets.listViewMode")).toBe("cards");
  });

  it("filters policy sets by search query", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicySetsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("security-baseline")).toBeInTheDocument();
    });

    const searchInput = screen.getByTestId("policy-set-search-input");
    fireEvent.change(searchInput, { target: { value: "tagging" } });

    await waitFor(() => {
      expect(screen.queryByText("security-baseline")).not.toBeInTheDocument();
      expect(screen.getByText("tagging-rules")).toBeInTheDocument();
      expect(screen.queryByText("advisory-checks")).not.toBeInTheDocument();
    });

    // Clear search
    fireEvent.change(searchInput, { target: { value: "" } });

    await waitFor(() => {
      expect(screen.getByText("security-baseline")).toBeInTheDocument();
      expect(screen.getByText("tagging-rules")).toBeInTheDocument();
      expect(screen.getByText("advisory-checks")).toBeInTheDocument();
    });
  });

  it("displays empty state when search finds no results and allows clearing filters", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicySetsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("security-baseline")).toBeInTheDocument();
    });

    const searchInput = screen.getByTestId("policy-set-search-input");
    fireEvent.change(searchInput, { target: { value: "non-existent-policy" } });

    await waitFor(() => {
      expect(
        screen.getByText("No policy sets match your search and filter criteria.")
      ).toBeInTheDocument();
    });

    const clearButton = screen.getByTestId("empty-clear-filters-btn");
    fireEvent.click(clearButton);

    await waitFor(() => {
      expect(screen.getByText("security-baseline")).toBeInTheDocument();
    });
  });

  it("renders policy set title as a link pointing to edit page", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicySetsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      const titleLink = screen.getByTestId("policy-set-title-link-ps-1");
      expect(titleLink).toBeInTheDocument();
      expect(titleLink).toHaveAttribute(
        "href",
        "/organizations/org-1/settings/policies/edit/ps-1"
      );
    });
  });

  it("renders empty state when no policy sets configured", async () => {
    getMock.mockResolvedValue({ data: { data: [] } });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies"
            element={<PolicySetsSettings managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("No Policy Sets Configured")).toBeInTheDocument();
    });
  });

  it("renders create form when editorMode is new", async () => {
    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies/new"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies/new"
            element={<PolicySetsSettings editorMode="new" managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByRole("heading", { name: "Create Policy Set" })).toBeInTheDocument();
    expect(screen.getByLabelText(/Policy Set Name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Enforcement Level/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Create Policy Set/i })).toBeInTheDocument();
  });
});
