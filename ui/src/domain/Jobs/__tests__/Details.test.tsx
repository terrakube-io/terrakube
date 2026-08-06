import { render, screen, waitFor } from "@testing-library/react";
import { DetailsJob } from "../Details";

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

const useStructuredOutputStreamMock = jest.fn();

jest.mock("../../../hooks", () => ({
  ...jest.requireActual("../../../hooks"),
  useStructuredOutputStream: (...args: unknown[]) => useStructuredOutputStreamMock(...args),
}));

describe("DetailsJob apply structured output", () => {
  beforeEach(() => {
    useStructuredOutputStreamMock.mockReturnValue(null);
  });

  it("uses the live structured-output stream while the job is running, not just the poll", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({ data: {} });
      }

      return Promise.resolve({
        data: {
          data: {
            id: "1",
            attributes: { status: "running" },
          },
          included: [
            {
              id: "step-2",
              type: "step",
              attributes: { name: "Apply", status: "running", stepNumber: "2" },
            },
          ],
        },
      });
    });

    useStructuredOutputStreamMock.mockReturnValue({
      phase: "apply",
      changes: {
        "step-2": [
          {
            address: "aws_instance.live",
            action: "create",
            actions: ["create"],
            after: { id: "i-live" },
            status: "applying",
          },
        ],
      },
      jobDiagnostics: {},
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /aws_instance\.live/i })).toBeInTheDocument();
    });
  });

  it("renders the structured component for an apply step when applyStructuredOutput is present", async () => {
    getMock.mockImplementation((url: string) => {
      if (url.includes("/context/v1/")) {
        return Promise.resolve({
          data: {
            applyStructuredOutput: {
              "step-2": [
                {
                  address: "aws_instance.example",
                  action: "create",
                  actions: ["create"],
                  after: { id: "i-123" },
                  status: "applied",
                },
              ],
            },
          },
        });
      }

      return Promise.resolve({
        data: {
          data: {
            id: "1",
            attributes: { status: "completed" },
          },
          included: [
            {
              id: "step-2",
              type: "step",
              attributes: { name: "Apply", status: "completed", stepNumber: "2" },
            },
          ],
        },
      });
    });

    render(<DetailsJob jobId="1" />);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /aws_instance\.example/i })).toBeInTheDocument();
    });
  });
});
