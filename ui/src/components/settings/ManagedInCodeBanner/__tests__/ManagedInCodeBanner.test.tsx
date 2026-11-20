import { render, screen } from "@testing-library/react";
import { axe, toHaveNoViolations } from "jest-axe";
import { useManagedInCode } from "@/modules/organizations/managedInCode";
import ManagedInCodeBanner from "../ManagedInCodeBanner";

expect.extend(toHaveNoViolations);

jest.mock("@/modules/organizations/managedInCode");
const managed = useManagedInCode as jest.Mock;

describe("ManagedInCodeBanner", () => {
  it("shows nothing for an organization that is not kept in code", () => {
    managed.mockReturnValue(null);
    const { container } = render(<ManagedInCodeBanner orgId="org-1" />);

    expect(container).toBeEmptyDOMElement();
  });

  it("says edits are reverted and links to the repository", () => {
    managed.mockReturnValue({ repository: "https://github.com/example/x", readOnly: false });
    render(<ManagedInCodeBanner orgId="org-1" />);

    expect(screen.getByText(/Edits here are reverted on the next apply/)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Open repository" });
    expect(link).toHaveAttribute("href", "https://github.com/example/x");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("says the settings are read-only in that mode, and has no link without a safe address", () => {
    managed.mockReturnValue({ repository: undefined, readOnly: true });
    render(<ManagedInCodeBanner orgId="org-1" />);

    expect(screen.getByText(/read-only here/)).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("has no accessibility violations", async () => {
    managed.mockReturnValue({ repository: "https://github.com/example/x", readOnly: false });
    const { container } = render(<ManagedInCodeBanner orgId="org-1" />);

    expect(await axe(container)).toHaveNoViolations();
  });
});
