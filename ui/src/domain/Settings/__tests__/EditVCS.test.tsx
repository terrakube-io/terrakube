import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EditVCS } from "../EditVCS";
import axiosInstance from "../../../config/axiosConfig";

jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useParams: () => ({ orgid: "org-1" }),
}));

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), patch: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

describe("EditVCS GitHub App webhook", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (window as any)._env_ = { REACT_APP_TERRAKUBE_API_URL: "https://api.example.com/api/v1" };
  });

  const load = (attributes: object) =>
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: { data: { id: "vcs-1", attributes: { name: "app", vcsType: "GITHUB", clientId: "1", ...attributes } } },
    });

  it("shows webhook URL and secret when enabled on a standalone app", async () => {
    load({ connectionType: "STANDALONE", appWebhookEnabled: true });
    render(<EditVCS vcsId="vcs-1" setMode={jest.fn()} loadVCS={jest.fn()} />);
    expect(await screen.findByDisplayValue("https://api.example.com/webhook/github-app/vcs-1")).toBeInTheDocument();
    expect(screen.getByLabelText("Webhook secret")).toBeInTheDocument();
  });

  it("keeps the stored secret when an already-enabled VCS is saved with a blank secret", async () => {
    load({ connectionType: "STANDALONE", appWebhookEnabled: true });
    (axiosInstance.patch as jest.Mock).mockResolvedValue({});
    render(<EditVCS vcsId="vcs-1" setMode={jest.fn()} loadVCS={jest.fn()} />);
    await screen.findByLabelText("Webhook secret");

    fireEvent.click(screen.getByRole("button", { name: "Update VCS provider" }));

    await waitFor(() => expect(axiosInstance.patch).toHaveBeenCalled());
    const attributes = (axiosInstance.patch as jest.Mock).mock.calls[0][1].data.attributes;
    expect(attributes.appWebhookEnabled).toBe(true);
    expect(attributes).not.toHaveProperty("webhookSecret");
  });

  it("requires a secret when enabling App webhook mode for the first time", async () => {
    load({ connectionType: "STANDALONE", appWebhookEnabled: false });
    render(<EditVCS vcsId="vcs-1" setMode={jest.fn()} loadVCS={jest.fn()} />);
    fireEvent.click(await screen.findByRole("switch"));
    await screen.findByLabelText("Webhook secret");

    fireEvent.click(screen.getByRole("button", { name: "Update VCS provider" }));

    expect(await screen.findByText("Webhook secret is required")).toBeInTheDocument();
    expect(axiosInstance.patch).not.toHaveBeenCalled();
  });

  it("hides webhook controls for OAuth connections", async () => {
    load({ connectionType: "OAUTH" });
    render(<EditVCS vcsId="vcs-1" setMode={jest.fn()} loadVCS={jest.fn()} />);
    await screen.findByDisplayValue("app");
    expect(screen.queryByText("Receive events via the GitHub App webhook")).not.toBeInTheDocument();
  });

  it("warns that turning App webhook mode off re-creates repository webhooks", async () => {
    load({ connectionType: "STANDALONE", appWebhookEnabled: true });
    render(<EditVCS vcsId="vcs-1" setMode={jest.fn()} loadVCS={jest.fn()} />);
    await screen.findByLabelText("Webhook secret");
    expect(screen.queryByText(/re-creates repository webhooks/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("switch"));

    expect(await screen.findByText(/re-creates repository webhooks/)).toBeInTheDocument();
  });
});
