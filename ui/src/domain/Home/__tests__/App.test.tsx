import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import type { createBrowserRouter } from "react-router-dom";

type Router = ReturnType<typeof createBrowserRouter>;

const mockUseAuth = jest.fn();

jest.mock("../../../config/authConfig", () => ({
  useAuth: () => mockUseAuth(),
}));

jest.mock("../../Login/Login", () => ({
  __esModule: true,
  default: () => <div>login page</div>,
}));

jest.mock("@/modules/organizations/OrganizationSummaryContext", () => ({
  OrganizationSummaryProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

// Shows where the app's router starts instead of rendering the whole app.
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  RouterProvider: ({ router }: { router: Router }) => (
    <div data-testid="router-location">
      {router.state.location.pathname}
      {router.state.location.search}
    </div>
  ),
}));

const runPath = "/organizations/org-1/workspaces/ws-1/runs/run-1";
const events = { addAccessTokenExpired: () => () => {}, addUserSignedOut: () => () => {} };
const signedIn = { isLoading: false, isAuthenticated: true, error: undefined, events, removeUser: jest.fn() };

describe("App", () => {
  beforeEach(() => {
    window._env_ = {
      REACT_APP_AUTHORITY: "https://auth.example.com/dex",
      REACT_APP_CLIENT_ID: "terrakube-ui",
      REACT_APP_REDIRECT_URI: "",
      REACT_APP_SCOPE: "openid profile email",
      REACT_APP_TERRAKUBE_API_URL: "https://api.example.com/api/v1/",
      REACT_APP_TERRAKUBE_VERSION: "test",
      REACT_APP_REGISTRY_URI: "https://registry.example.com",
    };
  });

  afterEach(() => window.history.replaceState({}, "", "/"));

  // App keeps its router and callback state for the page's life, so each test loads it afresh
  // after setting the URL the page opens on.
  const loadApp = (): typeof import("../App").default => {
    let App!: typeof import("../App").default;
    // Share this file's React so the isolated App renders with the same hooks dispatcher.
    const react = jest.requireActual<typeof import("react")>("react");
    const jsxRuntime = jest.requireActual<typeof import("react/jsx-runtime")>("react/jsx-runtime");
    jest.isolateModules(() => {
      jest.doMock("react", () => react);
      jest.doMock("react/jsx-runtime", () => jsxRuntime);
      App = jest.requireActual<typeof import("../App")>("../App").default;
    });
    return App;
  };

  it("starts the router on the page restored after sign-in, not on the callback URL", () => {
    window.history.replaceState({}, "", "/?code=abc&state=xyz");
    const App = loadApp();
    // The provider reports the user as signed in while the URL still carries the callback parameters.
    mockUseAuth.mockReturnValue(signedIn);

    const { rerender } = render(<App />);
    expect(screen.queryByTestId("router-location")).not.toBeInTheDocument();

    // onSigninCallback then rewrites the URL to the page the user was opening.
    window.history.replaceState({}, "", `${runPath}?tab=logs`);
    mockUseAuth.mockReturnValue({ ...signedIn });
    rerender(<App />);

    expect(screen.getByTestId("router-location")).toHaveTextContent(`${runPath}?tab=logs`);
  });

  it("shows sign-in after a failed sign-in callback", () => {
    window.history.replaceState({}, "", "/?error=access_denied&state=xyz");
    const App = loadApp();
    mockUseAuth.mockReturnValue({ ...signedIn, isAuthenticated: false, error: new Error("Sign-in failed") });

    render(<App />);

    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  it("does not wait again when a renewed session clears the error of a failed callback", () => {
    window.history.replaceState({}, "", "/?code=stale&state=xyz");
    const App = loadApp();
    mockUseAuth.mockReturnValue({ ...signedIn, isAuthenticated: false, error: new Error("No matching state") });

    const { rerender } = render(<App />);
    expect(screen.getByText("login page")).toBeInTheDocument();

    // A silent renew loads the stored session: signed in, error cleared, stale parameters still in the URL.
    mockUseAuth.mockReturnValue({ ...signedIn });
    rerender(<App />);

    expect(screen.getByTestId("router-location")).toHaveTextContent("/");
  });
});
