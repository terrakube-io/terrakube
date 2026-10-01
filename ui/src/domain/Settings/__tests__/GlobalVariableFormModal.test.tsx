import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { Form } from "antd";
import GlobalVariableFormModal from "../components/GlobalVariableFormModal";
import { CreateVariableForm } from "@/domain/types";

const TestWrapper = ({
  mode,
  variableKey,
  existingKeys,
  onSubmit,
}: {
  mode: "create" | "edit";
  variableKey?: string;
  existingKeys: string[];
  onSubmit: (values: CreateVariableForm) => void;
}) => {
  const [form] = Form.useForm<CreateVariableForm>();
  return (
    <GlobalVariableFormModal
      open={true}
      mode={mode}
      variableKey={variableKey}
      existingKeys={existingKeys}
      form={form}
      onCancel={jest.fn()}
      onSubmit={onSubmit}
    />
  );
};

describe("GlobalVariableFormModal", () => {
  it("rejects duplicate key in create mode", async () => {
    const onSubmit = jest.fn();
    render(<TestWrapper mode="create" existingKeys={["EXISTING_KEY", "DB_HOST"]} onSubmit={onSubmit} />);

    const keyInput = screen.getByLabelText("Key");
    fireEvent.change(keyInput, { target: { value: "DB_HOST" } });

    const okButton = screen.getByRole("button", { name: "Save global variable" });
    fireEvent.click(okButton);

    await waitFor(() => {
      expect(screen.getByText("A global variable with this key already exists")).toBeInTheDocument();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("accepts unique key in create mode", async () => {
    const onSubmit = jest.fn();
    render(<TestWrapper mode="create" existingKeys={["EXISTING_KEY", "DB_HOST"]} onSubmit={onSubmit} />);

    fireEvent.change(screen.getByLabelText("Key"), { target: { value: "NEW_KEY" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "some-val" } });
    fireEvent.change(screen.getByLabelText("Description"), { target: { value: "some-desc" } });

    // Category select
    fireEvent.mouseDown(screen.getByLabelText("Category"));
    const option = await screen.findByText("Terraform Variable");
    fireEvent.click(option);

    const okButton = screen.getByRole("button", { name: "Save global variable" });
    fireEvent.click(okButton);

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled();
    });
    expect(screen.queryByText("A global variable with this key already exists")).not.toBeInTheDocument();
  });

  it("permits keeping the same key in edit mode", async () => {
    const onSubmit = jest.fn();
    render(
      <TestWrapper mode="edit" variableKey="DB_HOST" existingKeys={["EXISTING_KEY", "DB_HOST"]} onSubmit={onSubmit} />
    );

    fireEvent.change(screen.getByLabelText("Key"), { target: { value: "DB_HOST" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "updated-val" } });
    fireEvent.change(screen.getByLabelText("Description"), { target: { value: "updated-desc" } });

    fireEvent.mouseDown(screen.getByLabelText("Category"));
    const option = await screen.findByText("Terraform Variable");
    fireEvent.click(option);

    const okButton = screen.getByRole("button", { name: "Save global variable" });
    fireEvent.click(okButton);

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled();
    });
    expect(screen.queryByText("A global variable with this key already exists")).not.toBeInTheDocument();
  });

  it("rejects changing key to another existing key in edit mode", async () => {
    const onSubmit = jest.fn();
    render(
      <TestWrapper mode="edit" variableKey="DB_HOST" existingKeys={["EXISTING_KEY", "DB_HOST"]} onSubmit={onSubmit} />
    );

    fireEvent.change(screen.getByLabelText("Key"), { target: { value: "EXISTING_KEY" } });

    const okButton = screen.getByRole("button", { name: "Save global variable" });
    fireEvent.click(okButton);

    await waitFor(() => {
      expect(screen.getByText("A global variable with this key already exists")).toBeInTheDocument();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
