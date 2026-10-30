import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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

describe("CreateJob new run modal", () => {
  it("offers up to three templates as explained choices and warns about destroy", async () => {
    getMock.mockImplementation((url: string) => {
      if (typeof url === "string" && url.includes("/template")) {
        return Promise.resolve({
          data: {
            data: [
              { id: "t-plan", attributes: { name: "Plan", description: "Plan only" } },
              { id: "t-apply", attributes: { name: "Plan and Apply", description: "Plan, then apply" } },
              { id: "t-destroy", attributes: { name: "Terraform-Destroy", description: "Running terraform destroy" } },
              { id: "t-cli", attributes: { name: "Terraform-Plan/Apply-Cli", description: "CLI" } },
            ],
          },
        });
      }
      return Promise.resolve({ data: { data: { attributes: { branch: "main", defaultTemplate: "t-plan" } } } });
    });

    render(<CreateJob changeJob={jest.fn()} planJob={true} />);
    await waitFor(() => expect(screen.getByRole("button", { name: /run now/i })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: /run now/i }));

    expect(await screen.findByText("Start a run")).toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    expect(screen.getByRole("radio", { name: /^Plan Plan only/ })).toBeChecked();
    expect(screen.getByText("Destroys every resource this workspace manages.")).toBeInTheDocument();
    expect(screen.queryByText("Terraform-Plan/Apply-Cli")).not.toBeInTheDocument();
  });
});

describe("CreateJob on a workspace that restricts apply to its branch", () => {
  const tcl = (yaml: string) => window.btoa(yaml);
  const plan = tcl('flow:\n  - type: "terraformPlan"\n    step: 100\n');
  const planAndApply = tcl(
    'flow:\n  - type: "terraformPlan"\n    step: 100\n  - type: "terraformApply"\n    step: 200\n'
  );

  beforeEach(() => {
    getMock.mockImplementation((url: string) => {
      if (typeof url === "string" && url.includes("/template")) {
        return Promise.resolve({
          data: {
            data: [
              { id: "t-plan", attributes: { name: "Plan", description: "Plan only", tcl: plan } },
              {
                id: "t-apply",
                attributes: { name: "Plan and Apply", description: "Plan, then apply", tcl: planAndApply },
              },
            ],
          },
        });
      }
      return Promise.resolve({
        data: {
          data: {
            attributes: { branch: "main, develop", defaultTemplate: "t-apply", restrictApplyToConfiguredBranch: true },
          },
        },
      });
    });
  });

  const open = async () => {
    render(<CreateJob changeJob={jest.fn()} planJob={true} />);
    await waitFor(() => expect(screen.getByRole("button", { name: /run now/i })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: /run now/i }));
    await screen.findByText("Start a run");
  };

  it("offers every template on the first branch of the workspace", async () => {
    await open();

    expect(await screen.findByRole("radio", { name: /^Plan and Apply/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /^Plan Plan only/ })).toBeInTheDocument();
    expect(screen.getByLabelText("Branch")).toHaveValue("main");
  });

  it("offers only plan-only templates on another branch", async () => {
    await open();

    fireEvent.change(screen.getByLabelText("Branch"), { target: { value: "feature/x" } });

    await waitFor(() => expect(screen.queryByRole("radio", { name: /^Plan and Apply/ })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("radio", { name: /^Plan Plan only/ })).toBeChecked());
    expect(screen.getByText(/only plan-only templates are offered/)).toBeInTheDocument();
  });

  it("keeps every template when the branch box is emptied, as the API treats it as the workspace branch", async () => {
    await open();

    fireEvent.change(screen.getByLabelText("Branch"), { target: { value: "" } });

    expect(screen.getByRole("radio", { name: /^Plan and Apply/ })).toBeInTheDocument();
    expect(screen.queryByText(/only plan-only templates are offered/)).not.toBeInTheDocument();
  });

  it("explains why nothing can be chosen when the organization has no plan-only template", async () => {
    const original = getMock.getMockImplementation();
    getMock.mockImplementation((url: string) =>
      typeof url === "string" && url.includes("/template")
        ? Promise.resolve({
            data: {
              data: [{ id: "t-apply", attributes: { name: "Plan and Apply", description: "", tcl: planAndApply } }],
            },
          })
        : original!(url)
    );
    await open();

    fireEvent.change(screen.getByLabelText("Branch"), { target: { value: "feature/x" } });

    expect(await screen.findByText("No plan-only templates available in this organization")).toBeInTheDocument();
  });
});
