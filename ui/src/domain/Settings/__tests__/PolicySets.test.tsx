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
        notificationConfiguration: { data: { id: "notif-ps-1" } },
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

  const sampleIncluded = [
    {
      type: "notification_configuration",
      id: "notif-ps-1",
      attributes: {
        name: "SecOps Alerts",
        channelType: "SLACK",
      },
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    getMock.mockImplementation((url: string) => {
      if (url.includes("policy_set")) {
        return Promise.resolve({ data: { data: samplePolicySets, included: sampleIncluded } });
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
      expect(screen.getByText("Hard mandatory")).toBeInTheDocument();
      expect(screen.getByText("Soft mandatory")).toBeInTheDocument();
      expect(screen.getByText("Advisory")).toBeInTheDocument();
      expect(screen.getByText("Global")).toBeInTheDocument();
      expect(screen.getByText("2 attachments")).toBeInTheDocument();
      expect(screen.getByText("1 attachment")).toBeInTheDocument();
      expect(screen.getByText("Override team: secops")).toBeInTheDocument();
      expect(screen.getByText("Notification: SecOps Alerts")).toBeInTheDocument();
    });

    // Check filter elements and header action are present
    expect(screen.getByTestId("policy-set-search-input")).toBeInTheDocument();
    expect(screen.getByTestId("policy-set-category-select")).toBeInTheDocument();
    expect(screen.getByTestId("policy-set-scope-select")).toBeInTheDocument();
    expect(screen.getByTestId("policy-set-view-toggle")).toBeInTheDocument();
    expect(screen.getByTestId("add-policy-set-btn")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Create policy set/ })).toBeInTheDocument();
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
      expect(screen.getByText("No policy sets match these filters.")).toBeInTheDocument();
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
      expect(titleLink).toHaveAttribute("href", "/organizations/org-1/settings/policies/edit/ps-1");
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
      expect(
        screen.getByText("No policy sets yet. Create one to check plans against OPA policies.")
      ).toBeInTheDocument();
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

    expect(screen.getByRole("heading", { name: "Create policy set" })).toBeInTheDocument();
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /Hard mandatory/ })).toBeChecked();
    expect(screen.getByLabelText("Notification")).toBeInTheDocument();
    expect(screen.getByTestId("policy-set-override-team-select")).toBeInTheDocument();
    expect(screen.getByLabelText("Override team")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create policy set" })).toBeInTheDocument();
  });

  it("renders authorized override team select allowing user to pick teams", async () => {
    const sampleTeams = [
      { id: "team-1", attributes: { name: "security-admins" } },
      { id: "team-2", attributes: { name: "platform-team" } },
    ];
    getMock.mockImplementation((url: string) => {
      if (url.includes("/team")) {
        return Promise.resolve({ data: { data: sampleTeams } });
      }
      return Promise.resolve({ data: { data: [] } });
    });

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

    await waitFor(() => {
      expect(screen.getByTestId("policy-set-override-team-select")).toBeInTheDocument();
    });

    expect(screen.getByLabelText("Override team")).toBeInTheDocument();
  });

  it("renders edit form with pre-populated override team in select", async () => {
    const editItem = {
      id: "ps-2",
      attributes: {
        name: "tagging-rules",
        description: "Requires tags",
        enforcementLevel: "SOFT_MANDATORY",
        overrideTeam: "secops",
        repository: "https://github.com/org/tag-policies",
        branch: "main",
      },
      relationships: {},
    };

    getMock.mockImplementation((url: string) => {
      if (url.startsWith("policy_set/ps-2")) {
        return Promise.resolve({ data: { data: editItem } });
      }
      if (url.includes("/team")) {
        return Promise.resolve({
          data: {
            data: [
              { id: "team-1", attributes: { name: "secops" } },
              { id: "team-2", attributes: { name: "security-admins" } },
            ],
          },
        });
      }
      return Promise.resolve({ data: { data: [] } });
    });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies/edit/ps-2"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies/edit/:id"
            element={<PolicySetsSettings editorMode="edit" editorId="ps-2" managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Edit policy set" })).toBeInTheDocument();
      expect(screen.getByText("secops")).toBeInTheDocument();
    });
  });

  it("renders OPA version input field with placeholder in create form", async () => {
    getMock.mockResolvedValue({ data: { data: [] } });

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

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Create policy set" })).toBeInTheDocument();
    });

    const opaVersionInput = screen.getByTestId("policy-set-opa-version-input");
    expect(opaVersionInput).toBeInTheDocument();
    expect(opaVersionInput).toHaveAttribute("placeholder", "Inherit system default (e.g. 1.20.2)");
    expect(screen.getByText("OPA releases")).toBeInTheDocument();
  });

  it("renders edit form with pre-populated opaVersion", async () => {
    const editItem = {
      id: "ps-3",
      attributes: {
        name: "custom-opa-set",
        description: "Custom OPA version test",
        enforcementLevel: "HARD_MANDATORY",
        opaVersion: "0.68.0",
        repository: "https://github.com/org/policies",
        branch: "main",
      },
      relationships: {},
    };

    getMock.mockImplementation((url: string) => {
      if (url.startsWith("policy_set/ps-3")) {
        return Promise.resolve({ data: { data: editItem } });
      }
      return Promise.resolve({ data: { data: [] } });
    });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies/edit/ps-3"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies/edit/:id"
            element={<PolicySetsSettings editorMode="edit" editorId="ps-3" managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Edit policy set" })).toBeInTheDocument();
    });

    const opaVersionInput = screen.getByTestId("policy-set-opa-version-input") as HTMLInputElement;
    expect(opaVersionInput.value).toBe("0.68.0");
  });

  it("deletes a policy set only after its name is typed", async () => {
    deleteMock.mockResolvedValue({});
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

    fireEvent.click(await screen.findByRole("button", { name: "Delete tagging-rules" }));
    const confirm = await screen.findByRole("button", { name: "Delete policy set" });
    expect(confirm).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Type the name to confirm"), { target: { value: "tagging-rules" } });
    fireEvent.click(confirm);

    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("policy_set/ps-2"));
  });

  it("offers deletion in a danger zone on the edit page", async () => {
    getMock.mockImplementation((url: string) =>
      url.startsWith("policy_set/ps-3")
        ? Promise.resolve({
            data: { data: { id: "ps-3", attributes: { name: "advisory-checks" }, relationships: {} } },
          })
        : Promise.resolve({ data: { data: [] } })
    );

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/settings/policies/edit/ps-3"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/policies/edit/:id"
            element={<PolicySetsSettings editorMode="edit" editorId="ps-3" managePermission={true} />}
          />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByRole("heading", { name: "Destruction and deletion" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete this policy set" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Update policy set" })).toBeInTheDocument();
  });
});
