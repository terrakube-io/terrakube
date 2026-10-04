import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { CreateModule } from "../Create";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));
jest.mock("@/hooks/useOrganizationName", () => ({ useOrganizationName: () => "acme" }));

const vcs = [{ id: "vcs-1", type: "vcs", attributes: { name: "github-main", vcsType: "GITHUB" } }];

const renderCreate = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/registry/create"]}>
      <Routes>
        <Route path="/organizations/:orgid/registry/create" element={<CreateModule />} />
        <Route path="/organizations/:orgid/registry/:id" element={<p>Module page</p>} />
      </Routes>
    </MemoryRouter>
  );

const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe("CreateModule", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (axiosInstance.get as jest.Mock).mockImplementation((url: string) =>
      Promise.resolve({ data: { data: url.endsWith("/vcs") ? vcs : [] } })
    );
    (axiosInstance.post as jest.Mock).mockResolvedValue({ status: 201, data: { data: { id: "m-1" } } });
  });

  const fillModule = () => {
    fill("Repository URL", "https://github.com/acme/terraform-aws-vpc.git");
    fireEvent.blur(screen.getByLabelText("Repository URL"));
    fill("Description", "VPC module");
  };

  it("fills name and provider from the repository and posts the same payload as before", async () => {
    renderCreate();
    fillModule();
    expect(screen.getByLabelText("Name")).toHaveValue("vpc");
    expect(screen.getByLabelText("Provider")).toHaveValue("aws");

    fireEvent.click(screen.getByRole("button", { name: "Publish module" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    const [url, body] = (axiosInstance.post as jest.Mock).mock.calls[0];
    expect(url).toBe("organization/org-1/module");
    expect(body).toEqual({
      data: {
        type: "module",
        attributes: {
          name: "vpc",
          description: "VPC module",
          provider: "aws",
          source: "https://github.com/acme/terraform-aws-vpc.git",
          folder: null,
          tagPrefix: null,
        },
      },
    });
    expect(await screen.findByText("Module page")).toBeInTheDocument();
  });

  it("links the chosen VCS connection", async () => {
    renderCreate();
    fireEvent.click(await screen.findByRole("radio", { name: "github-main" }));
    fillModule();

    fireEvent.click(screen.getByRole("button", { name: "Publish module" }));

    await waitFor(() => expect(axiosInstance.post).toHaveBeenCalled());
    expect((axiosInstance.post as jest.Mock).mock.calls[0][1].data.relationships).toEqual({
      vcs: { data: { type: "vcs", id: "vcs-1" } },
    });
  });
});
