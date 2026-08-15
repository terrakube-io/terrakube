import { render, screen, waitFor } from "@testing-library/react";
import { CreateJob } from "../Create";

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
  default: { get: (...args: unknown[]) => getMock(...args) },
  axiosClient: { get: (...args: unknown[]) => getMock(...args) },
}));

jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => jest.fn(),
}));

describe("CreateJob Run now button", () => {
  beforeEach(() => {
    // On mount CreateJob calls loadTemplates() (expects response.data.data to be an array)
    // and loadBranch() (expects response.data.data.attributes). Return shapes for both.
    getMock.mockImplementation((url: string) => {
      if (typeof url === "string" && url.includes("/template")) {
        return Promise.resolve({ data: { data: [] } });
      }
      return Promise.resolve({ data: { data: { attributes: { branch: "main", defaultTemplate: undefined } } } });
    });
  });

  const runNowButton = () => screen.getByRole("button", { name: /run now/i });

  it("enables Run now when there is no disabled reason (e.g. VCS workspace)", async () => {
    render(<CreateJob changeJob={jest.fn()} planJob={true} />);
    await waitFor(() => expect(runNowButton()).toBeEnabled());
  });

  it("disables Run now when a disabled reason is provided (unconfigured CLI/API workspace)", async () => {
    render(
      <CreateJob
        changeJob={jest.fn()}
        planJob={true}
        disabledReason="This CLI/API driven workspace has no applied configuration yet."
      />
    );
    await waitFor(() => expect(runNowButton()).toBeDisabled());
  });

  it("disables Run now when the user lacks plan permission", async () => {
    render(<CreateJob changeJob={jest.fn()} planJob={false} />);
    await waitFor(() => expect(runNowButton()).toBeDisabled());
  });
});
