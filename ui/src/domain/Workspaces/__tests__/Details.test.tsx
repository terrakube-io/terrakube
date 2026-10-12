import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { WorkspaceDetails } from "../Details";

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

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => getMock(...args),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

// Details.tsx imports from '../../types.js' and '../Icons.jsx' with file extensions.
// Virtual mocks allow Jest to resolve these ESM extension imports in the test environment
// without requiring edits to untouched shared source files.
jest.mock("../../types.js", () => jest.requireActual("../../types"), { virtual: true });
jest.mock("../Icons.jsx", () => jest.requireActual("../Icons"), { virtual: true });
jest.mock("../ResourceDrawer", () => ({ ResourceDrawer: () => null }));

jest.mock("../../../hooks", () => ({
  usePolling: jest.fn(),
  useJobStatusSubscription: jest.fn(),
}));

describe("WorkspaceDetails", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sessionStorage.clear();
  });

  it("renders correctly with Remote fallback when executionMode is null (e.g. CLI driven workflow)", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/workspace/ws-1")) {
        return Promise.resolve({
          data: {
            data: {
              id: "ws-1",
              type: "workspace",
              attributes: {
                name: "sample-workspace",
                description: "Test workspace",
                source:
                  "https://terrakube-api.localhost/remote/tfe/v2/configuration-versions/1/terraformContent.tar.gz",
                branch: "remote-content",
                executionMode: null,
                terraformVersion: "1.5.0",
                folder: "/",
              },
              relationships: {
                organization: { data: { id: "org-1" } },
              },
            },
            included: [
              {
                id: "org-1",
                type: "organization",
                attributes: { name: "test-org" },
              },
            ],
          },
        });
      }
      return Promise.resolve({ data: { data: [] } });
    });

    render(
      <MemoryRouter initialEntries={["/organizations/org-1/workspaces/ws-1"]}>
        <Routes>
          <Route
            path="/organizations/:orgid/workspaces/:id"
            element={<WorkspaceDetails setOrganizationName={jest.fn()} setWorkspaceManageState={jest.fn()} />}
          />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("CLI/API-driven workflow")).toBeInTheDocument();
    });

    expect(screen.getByText("Remote")).toBeInTheDocument();
  });
});
