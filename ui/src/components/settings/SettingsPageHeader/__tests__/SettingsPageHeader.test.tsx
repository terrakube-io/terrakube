import { render, screen } from "@testing-library/react";
import SettingsPageHeader from "../SettingsPageHeader";

describe("SettingsPageHeader", () => {
  it("ends the description with a documentation link that opens in a new tab", () => {
    render(<SettingsPageHeader title="Teams" description="Manage teams." docUrl="https://docs.example/teams" />);
    const link = screen.getByRole("link", { name: /Documentation/ });
    expect(link).toHaveAttribute("href", "https://docs.example/teams");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.closest(".ant-typography-secondary")).toHaveTextContent("Manage teams. Documentation");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("shows no link without a docUrl", () => {
    render(<SettingsPageHeader title="Teams" description="Manage teams." />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
