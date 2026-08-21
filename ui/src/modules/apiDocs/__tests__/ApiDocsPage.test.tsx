import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ApiDocsPage } from "../ApiDocsPage";
import getUserFromStorage from "@/config/authUser";
import { axiosRegistry } from "@/config/axiosConfig";

jest.mock("@/config/authUser");
const mockGetUserFromStorage = getUserFromStorage as jest.Mock;

jest.mock("@/config/axiosConfig", () => ({
  axiosRegistry: { get: jest.fn() },
}));
const mockAxiosGet = axiosRegistry.get as jest.Mock;

const apiReferenceMock = jest.fn<null, [unknown]>(() => null);
jest.mock("@scalar/api-reference-react", () => ({
  __esModule: true,
  ApiReferenceReact: (props: unknown) => apiReferenceMock(props),
}));

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

const fakeSpec = { openapi: "3.0.1", info: { title: "Elide Service" }, paths: {} };

function renderApiDocsPage() {
  return render(
    <MemoryRouter>
      <ApiDocsPage />
    </MemoryRouter>
  );
}

describe("ApiDocsPage", () => {
  beforeEach(() => {
    apiReferenceMock.mockClear();
    mockGetUserFromStorage.mockReset();
    mockAxiosGet.mockReset();
    mockAxiosGet.mockResolvedValue({ data: fakeSpec });
    mockNavigate.mockClear();
  });

  it("fetches the spec from the API's /doc endpoint and passes it to Scalar as content", async () => {
    renderApiDocsPage();

    await waitFor(() => expect(apiReferenceMock).toHaveBeenCalled());

    expect(mockAxiosGet).toHaveBeenCalledWith("/doc");
    expect(apiReferenceMock).toHaveBeenCalledWith(
      expect.objectContaining({ configuration: expect.objectContaining({ content: fakeSpec }) })
    );
  });

  it("attaches the signed-in user's bearer token to every request Scalar's 'Try it' fires", async () => {
    mockGetUserFromStorage.mockReturnValue({ access_token: "token-123" });
    renderApiDocsPage();
    await waitFor(() => expect(apiReferenceMock).toHaveBeenCalled());

    const { configuration } = apiReferenceMock.mock.calls[0][0] as {
      configuration: { onBeforeRequest: (input: { requestBuilder: { headers: { set: jest.Mock } } }) => void };
    };
    const headers = { set: jest.fn() };
    configuration.onBeforeRequest({ requestBuilder: { headers } });

    expect(headers.set).toHaveBeenCalledWith("Authorization", "Bearer token-123");
  });

  it("leaves the Authorization header unset when there is no signed-in user", async () => {
    mockGetUserFromStorage.mockReturnValue(null);
    renderApiDocsPage();
    await waitFor(() => expect(apiReferenceMock).toHaveBeenCalled());

    const { configuration } = apiReferenceMock.mock.calls[0][0] as {
      configuration: { onBeforeRequest: (input: { requestBuilder: { headers: { set: jest.Mock } } }) => void };
    };
    const headers = { set: jest.fn() };
    configuration.onBeforeRequest({ requestBuilder: { headers } });

    expect(headers.set).not.toHaveBeenCalled();
  });

  it("points Try It requests at the API's origin instead of resolving the spec's relative server URL against the docs page's own origin", async () => {
    renderApiDocsPage();

    await waitFor(() => expect(apiReferenceMock).toHaveBeenCalled());

    expect(apiReferenceMock).toHaveBeenCalledWith(
      expect.objectContaining({
        configuration: expect.objectContaining({ baseServerURL: "https://terrakube-api.test" }),
      })
    );
  });

  it("navigates back to the app shell when 'Back to Terrakube' is clicked", async () => {
    renderApiDocsPage();
    await waitFor(() => expect(apiReferenceMock).toHaveBeenCalled());

    fireEvent.click(screen.getByText("Back to Terrakube"));

    expect(mockNavigate).toHaveBeenCalledWith("/");
  });
});
