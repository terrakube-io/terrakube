import React from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { PolicySetParameters } from "../PolicySetParameters";
import axiosInstance from "../../../../config/axiosConfig";

jest.mock("../../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: jest.fn(),
    delete: jest.fn(),
    post: jest.fn(),
    patch: jest.fn(),
  },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

describe("PolicySetParameters", () => {
  const sampleParameters = [
    {
      id: "param-1",
      type: "policy_set_parameter",
      attributes: {
        key: "max_deletions",
        value: "5",
        description: "Maximum allowed deletions before SecOps approval",
      },
    },
    {
      id: "param-2",
      type: "policy_set_parameter",
      attributes: {
        key: "allowed_regions",
        value: '["us-east-1", "us-west-2"]',
        description: "Permitted deployment regions",
      },
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: {
        data: sampleParameters,
      },
    });
  });

  it("renders parameters list fetched from API", async () => {
    render(<PolicySetParameters policySetId="ps-100" managePermission={true} />);

    expect(await screen.findByText("max_deletions")).toBeInTheDocument();
    expect(screen.getByText("allowed_regions")).toBeInTheDocument();
    expect(screen.getByText("Maximum allowed deletions before SecOps approval")).toBeInTheDocument();
    expect(screen.getByText('["us-east-1", "us-west-2"]')).toBeInTheDocument();
  });

  it("filters parameters based on search query", async () => {
    render(<PolicySetParameters policySetId="ps-100" managePermission={true} />);

    expect(await screen.findByText("max_deletions")).toBeInTheDocument();
    expect(screen.getByText("allowed_regions")).toBeInTheDocument();

    const searchInput = screen.getByLabelText("Search parameters by key or description");
    fireEvent.change(searchInput, { target: { value: "max" } });

    expect(screen.getByText("max_deletions")).toBeInTheDocument();
    expect(screen.queryByText("allowed_regions")).not.toBeInTheDocument();
  });

  it("opens modal and submits new parameter", async () => {
    (axiosInstance.post as jest.Mock).mockResolvedValue({
      data: {
        data: {
          id: "param-3",
          type: "policy_set_parameter",
          attributes: { key: "new_param", value: "true", description: "test" },
        },
      },
    });

    render(<PolicySetParameters policySetId="ps-100" managePermission={true} />);
    expect(await screen.findByText("max_deletions")).toBeInTheDocument();

    const addBtn = screen.getByTestId("add-parameter-btn");
    fireEvent.click(addBtn);

    expect(await screen.findByText("Add parameter", { selector: ".ant-modal-title" })).toBeInTheDocument();

    const keyInput = screen.getByLabelText("Key");
    const valInput = screen.getByLabelText("Value");
    const descInput = screen.getByLabelText("Description");

    fireEvent.change(keyInput, { target: { value: "new_param" } });
    fireEvent.change(valInput, { target: { value: "true" } });
    fireEvent.change(descInput, { target: { value: "test description" } });

    fireEvent.click(screen.getByRole("button", { name: "Add parameter" }));

    await waitFor(() => {
      expect(axiosInstance.post).toHaveBeenCalledWith(
        "policy_set/ps-100/parameters",
        expect.objectContaining({
          data: expect.objectContaining({
            type: "policy_set_parameter",
            attributes: expect.objectContaining({
              key: "new_param",
              value: "true",
              description: "test description",
            }),
          }),
        }),
        expect.anything()
      );
    });
  });

  it("opens edit modal and patches existing parameter", async () => {
    (axiosInstance.patch as jest.Mock).mockResolvedValue({
      data: {
        data: {
          id: "param-1",
          type: "policy_set_parameter",
          attributes: { key: "max_deletions", value: "10", description: "Updated" },
        },
      },
    });

    render(<PolicySetParameters policySetId="ps-100" managePermission={true} />);
    expect(await screen.findByText("max_deletions")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Edit parameter max_deletions" }));

    expect(await screen.findByText("Edit parameter max_deletions")).toBeInTheDocument();

    const valInput = screen.getByLabelText("Value");
    fireEvent.change(valInput, { target: { value: "10" } });

    fireEvent.click(screen.getByRole("button", { name: "Save parameter" }));

    await waitFor(() => {
      expect(axiosInstance.patch).toHaveBeenCalledWith(
        "policy_set/ps-100/parameters/param-1",
        expect.objectContaining({
          data: expect.objectContaining({
            id: "param-1",
            attributes: expect.objectContaining({
              key: "max_deletions",
              value: "10",
            }),
          }),
        }),
        expect.anything()
      );
    });
  });

  it("opens delete modal and deletes parameter", async () => {
    (axiosInstance.delete as jest.Mock).mockResolvedValue({});

    render(<PolicySetParameters policySetId="ps-100" managePermission={true} />);
    expect(await screen.findByText("max_deletions")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete parameter max_deletions" }));

    expect(await screen.findByText("Delete parameter", { selector: ".ant-modal-title" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete parameter" }));

    await waitFor(() => {
      expect(axiosInstance.delete).toHaveBeenCalledWith("policy_set/ps-100/parameters/param-1");
    });
  });

  it("disables add and action buttons when managePermission is false", async () => {
    render(<PolicySetParameters policySetId="ps-100" managePermission={false} />);

    expect(await screen.findByText("max_deletions")).toBeInTheDocument();
    expect(screen.getByTestId("add-parameter-btn")).toBeDisabled();

    expect(screen.getByRole("button", { name: "Edit parameter max_deletions" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete parameter max_deletions" })).toBeDisabled();
  });

  it("shows an empty state instead of an empty table", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [] } });
    render(<PolicySetParameters policySetId="ps-100" managePermission={true} />);

    expect(
      await screen.findByText("No parameters. Add one to pass a value to the rules in this policy set.")
    ).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Parameters (0)" })).toBeInTheDocument();
  });
});
