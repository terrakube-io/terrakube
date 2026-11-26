import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { mgr } from "@/config/authConfig";
import Login from "../Login";

jest.mock("@/config/authConfig", () => ({
  mgr: { signinRedirect: jest.fn() },
}));

const mockSigninRedirect = mgr.signinRedirect as jest.Mock;

describe("Login", () => {
  beforeEach(() => {
    mockSigninRedirect.mockReset();
  });

  afterEach(() => window.history.replaceState({}, "", "/"));

  it("carries the page being opened through sign-in so the user returns to it", async () => {
    mockSigninRedirect.mockResolvedValue(undefined);
    window.history.pushState({}, "", "/organizations/org-1/workspaces/ws-1/runs/run-1?tab=logs");

    render(<Login />);
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() =>
      expect(mockSigninRedirect).toHaveBeenCalledWith({
        state: "/organizations/org-1/workspaces/ws-1/runs/run-1?tab=logs",
      })
    );
  });

  it("shows an error instead of doing nothing when the identity provider is unreachable", async () => {
    mockSigninRedirect.mockRejectedValue(new Error("Failed to fetch"));

    render(<Login />);
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText(/unable to reach the identity provider/i)).toBeInTheDocument();
  });

  it("does not show an error when sign-in redirect succeeds", async () => {
    mockSigninRedirect.mockResolvedValue(undefined);

    render(<Login />);
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(mockSigninRedirect).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(/unable to reach the identity provider/i)).not.toBeInTheDocument();
  });
});
