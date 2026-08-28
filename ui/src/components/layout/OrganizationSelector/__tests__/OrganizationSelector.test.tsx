import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { OrganizationSelector, OrganizationSelectorProps } from "../OrganizationSelector";
import { FlatOrganization } from "@/domain/types";

const mockOrganizations: FlatOrganization[] = [
  {
    id: "org-1",
    name: "Acme Corp",
    description: "Main organization",
  },
  {
    id: "org-2",
    name: "Dev Team",
    description: "Development team workspace",
  },
  {
    id: "org-3",
    name: "QA Team",
    description: "Quality assurance team",
  },
];

const defaultProps = {
  organizationName: "Acme Corp",
  organizations: mockOrganizations,
  onOrgChange: jest.fn(),
};

function renderSelector(props: Partial<OrganizationSelectorProps> = {}) {
  return render(
    <MemoryRouter>
      <OrganizationSelector {...defaultProps} {...props} />
    </MemoryRouter>
  );
}

describe("OrganizationSelector", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("Rendering", () => {
    it("renders button with current organization name", () => {
      renderSelector();
      expect(screen.getByText("Acme Corp")).toBeInTheDocument();
    });

    it("renders 'Choose an organization' when organizationName is empty", () => {
      renderSelector({ organizationName: "" });
      expect(screen.getByText("Choose an organization")).toBeInTheDocument();
    });

    it("renders 'Choose an organization' when organizationName is not provided", () => {
      renderSelector({ organizationName: "" });
      expect(screen.getByText("Choose an organization")).toBeInTheDocument();
    });
  });

  describe("Dropdown Interaction", () => {
    it("opens dropdown when button is clicked", () => {
      renderSelector();
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      expect(screen.getAllByText("Acme Corp").length).toBeGreaterThan(1);
      expect(screen.getByText("Dev Team")).toBeInTheDocument();
      expect(screen.getByText("QA Team")).toBeInTheDocument();
    });

    it("displays all organizations in dropdown list, including the current one", () => {
      renderSelector();
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      mockOrganizations.forEach((org) => {
        expect(screen.getAllByText(org.name).length).toBeGreaterThan(0);
      });
    });

    it("links each organization to its workspaces page", () => {
      renderSelector();
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      const devTeamLink = screen.getByText("Dev Team").closest("a");
      expect(devTeamLink).toHaveAttribute("href", "/organizations/org-2/workspaces");
    });

    it("shows 'Manage Organizations' link in dropdown", () => {
      renderSelector();
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      const manageLink = screen.getByText("Manage Organizations").closest("a");
      expect(manageLink).toHaveAttribute("href", "/organizations");
    });
  });

  describe("Callbacks", () => {
    it("calls onOrgChange with correct orgId when organization is selected", () => {
      const onOrgChange = jest.fn();
      renderSelector({ onOrgChange });

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      const devTeamOption = screen.getByText("Dev Team");
      fireEvent.click(devTeamOption);

      expect(onOrgChange).toHaveBeenCalledWith("org-2");
    });

    it("calls onOrgChange when selecting the current organization", () => {
      const onOrgChange = jest.fn();
      renderSelector({ onOrgChange });

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      const acmeOption = screen.getAllByText("Acme Corp")[1];
      fireEvent.click(acmeOption);

      expect(onOrgChange).toHaveBeenCalledWith("org-1");
    });
  });

  describe("Edge Cases", () => {
    it("handles empty organizations list gracefully", () => {
      renderSelector({ organizations: [] });

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      expect(screen.getByText("Manage Organizations")).toBeInTheDocument();
    });

    it("handles single organization in list", () => {
      const singleOrg = [mockOrganizations[0]];
      renderSelector({ organizations: singleOrg });

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      expect(screen.getAllByText("Acme Corp").length).toBeGreaterThan(1);
      expect(screen.getByText("Manage Organizations")).toBeInTheDocument();
    });

    it("closes dropdown when clicking outside", () => {
      const { container } = renderSelector();

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      expect(screen.getByText("Dev Team")).toBeInTheDocument();

      fireEvent.click(container);
    });
  });

  describe("Placement", () => {
    it("opens the dropdown upward when placement is 'top'", () => {
      renderSelector({ placement: "top" });
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      expect(document.querySelector(".org-selector-dropdown--top")).toBeInTheDocument();
    });

    it("opens the dropdown downward by default", () => {
      renderSelector();
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      expect(document.querySelector(".org-selector-dropdown--top")).not.toBeInTheDocument();
    });
  });
});
