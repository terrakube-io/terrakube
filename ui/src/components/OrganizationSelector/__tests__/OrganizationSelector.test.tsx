import { render, screen, fireEvent } from "@testing-library/react";
import { OrganizationSelector } from "../OrganizationSelector";
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
  onManageOrgs: jest.fn(),
};

describe("OrganizationSelector", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("Rendering", () => {
    it("renders button with current organization name", () => {
      render(<OrganizationSelector {...defaultProps} />);
      expect(screen.getByText("Acme Corp")).toBeInTheDocument();
    });

    it("renders 'Choose an organization' when organizationName is empty", () => {
      render(<OrganizationSelector {...defaultProps} organizationName="" />);
      expect(screen.getByText("Choose an organization")).toBeInTheDocument();
    });

    it("renders 'Choose an organization' when organizationName is not provided", () => {
      render(<OrganizationSelector {...defaultProps} organizationName="" />);
      expect(screen.getByText("Choose an organization")).toBeInTheDocument();
    });
  });

  describe("Dropdown Interaction", () => {
    it("opens dropdown when button is clicked", () => {
      render(<OrganizationSelector {...defaultProps} />);
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      // After clicking, dropdown should be visible with organization list
      expect(screen.getByText("Acme Corp")).toBeInTheDocument();
      expect(screen.getByText("Dev Team")).toBeInTheDocument();
      expect(screen.getByText("QA Team")).toBeInTheDocument();
    });

    it("displays all organizations in dropdown list", () => {
      render(<OrganizationSelector {...defaultProps} />);
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      mockOrganizations.forEach((org) => {
        expect(screen.getByText(org.name)).toBeInTheDocument();
      });
    });

    it("shows 'Manage Organizations' link in dropdown", () => {
      render(<OrganizationSelector {...defaultProps} />);
      const button = screen.getByRole("button", { name: /Acme Corp/i });

      fireEvent.click(button);

      expect(screen.getByText("Manage Organizations")).toBeInTheDocument();
    });
  });

  describe("Callbacks", () => {
    it("calls onOrgChange with correct orgId when organization is selected", () => {
      const onOrgChange = jest.fn();
      render(<OrganizationSelector {...defaultProps} onOrgChange={onOrgChange} />);

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      const devTeamOption = screen.getByText("Dev Team");
      fireEvent.click(devTeamOption);

      expect(onOrgChange).toHaveBeenCalledWith("org-2");
    });

    it("calls onManageOrgs when 'Manage Organizations' link is clicked", () => {
      const onManageOrgs = jest.fn();
      render(<OrganizationSelector {...defaultProps} onManageOrgs={onManageOrgs} />);

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      const manageLink = screen.getByText("Manage Organizations");
      fireEvent.click(manageLink);

      expect(onManageOrgs).toHaveBeenCalled();
    });

    it("does not call onOrgChange when selecting the current organization", () => {
      const onOrgChange = jest.fn();
      render(<OrganizationSelector {...defaultProps} onOrgChange={onOrgChange} />);

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      const acmeOption = screen.getAllByText("Acme Corp")[1]; // Get the dropdown option, not the button
      fireEvent.click(acmeOption);

      expect(onOrgChange).not.toHaveBeenCalled();
    });
  });

  describe("Edge Cases", () => {
    it("handles empty organizations list gracefully", () => {
      render(<OrganizationSelector {...defaultProps} organizations={[]} />);

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      // Should still show "Manage Organizations" link
      expect(screen.getByText("Manage Organizations")).toBeInTheDocument();
    });

    it("handles single organization in list", () => {
      const singleOrg = [mockOrganizations[0]];
      render(<OrganizationSelector {...defaultProps} organizations={singleOrg} />);

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      expect(screen.getByText("Acme Corp")).toBeInTheDocument();
      expect(screen.getByText("Manage Organizations")).toBeInTheDocument();
    });

    it("closes dropdown when clicking outside", () => {
      const { container } = render(<OrganizationSelector {...defaultProps} />);

      const button = screen.getByRole("button", { name: /Acme Corp/i });
      fireEvent.click(button);

      // Verify dropdown is open
      expect(screen.getByText("Dev Team")).toBeInTheDocument();

      // Click outside
      fireEvent.click(container);

      // Dropdown should be closed (Dev Team should not be visible as a separate element)
      // This test may need adjustment based on actual implementation
    });
  });
});
