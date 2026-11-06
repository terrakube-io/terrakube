import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
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

  it("shows an error instead of doing nothing when the identity provider is unreachable", async () => {
    mockSigninRedirect.mockRejectedValue(new Error("Failed to fetch"));

    await act(async () => {
      render(<Login />);
    });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText(/unable to reach the identity provider/i)).toBeInTheDocument();
  });

  it("does not show an error when sign-in redirect succeeds", async () => {
    mockSigninRedirect.mockResolvedValue(undefined);

    await act(async () => {
      render(<Login />);
    });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(mockSigninRedirect).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(/unable to reach the identity provider/i)).not.toBeInTheDocument();
  });

  it("renders the layout with login-layout and login-container", async () => {
    let container: HTMLElement;
    await act(async () => {
      const rendered = render(<Login />);
      container = rendered.container;
    });
    const loginLayout = container!.querySelector(".login-layout");
    const loginContainer = container!.querySelector(".login-container");
    const loginCard = container!.querySelector(".login-card");

    expect(loginLayout).toBeInTheDocument();
    expect(loginContainer).toBeInTheDocument();
    expect(loginCard).toBeInTheDocument();
    expect(loginLayout).toContainElement(loginContainer);
    expect(loginContainer).toContainElement(loginCard);
  });
});
