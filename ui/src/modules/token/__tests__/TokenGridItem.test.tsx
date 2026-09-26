import { render, screen } from "@testing-library/react";
import TokenGridItem from "../TokenGridItem";
import { UserToken } from "@/modules/user/types";

const baseToken: UserToken = {
  id: "1",
  deleted: false,
  days: 30,
  description: "my token",
  createdDate: "2026-08-01T00:00:00Z",
  updatedDate: "2026-08-01T00:00:00Z",
  createdBy: "user@example.io",
  updatedBy: "user@example.io",
};

function renderItem(overrides: Partial<UserToken>) {
  return render(<TokenGridItem token={{ ...baseToken, ...overrides }} loading={false} onDelete={() => {}} />);
}

describe("TokenGridItem", () => {
  it("shows the CLI login badge for CLI_LOGIN tokens", () => {
    renderItem({ source: "CLI_LOGIN" });
    expect(screen.getByText("CLI login")).toBeInTheDocument();
  });

  it("does not show the CLI login badge for API tokens", () => {
    renderItem({ source: "API" });
    expect(screen.queryByText("CLI login")).not.toBeInTheDocument();
  });

  it("shows 'Never used' when lastUsedAt is absent", () => {
    renderItem({ lastUsedAt: null });
    expect(screen.getByText("Never used")).toBeInTheDocument();
  });

  it("shows a relative last-used time when lastUsedAt is present", () => {
    renderItem({ lastUsedAt: "2026-08-15T00:00:00Z" });
    expect(screen.getByText(/^Last used /)).toBeInTheDocument();
  });
});
