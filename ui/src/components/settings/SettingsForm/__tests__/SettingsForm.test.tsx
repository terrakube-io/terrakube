import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Form, Input } from "antd";
import SettingsForm from "../SettingsForm";

function renderForm(props: Partial<React.ComponentProps<typeof SettingsForm>> = {}) {
  const onFinish = jest.fn();
  render(
    <SettingsForm initialValues={{ name: "demo" }} onFinish={onFinish} {...props}>
      <Form.Item name="name" label="Name">
        <Input />
      </Form.Item>
    </SettingsForm>
  );
  return onFinish;
}

describe("SettingsForm", () => {
  it("defaults the save label to 'Save settings'", () => {
    renderForm();
    expect(screen.getByRole("button", { name: "Save settings" })).toBeInTheDocument();
  });

  it("uses a custom save label", () => {
    renderForm({ saveLabel: "Update organization" });
    expect(screen.getByRole("button", { name: "Update organization" })).toBeInTheDocument();
  });

  it("submits the form values from the save button", async () => {
    const onFinish = renderForm();
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
    await waitFor(() => expect(onFinish).toHaveBeenCalledWith({ name: "demo" }));
  });

  it("places the save button after the fields, outside any end-justified row", () => {
    renderForm();
    const button = screen.getByRole("button", { name: "Save settings" });
    expect(screen.getByLabelText("Name").compareDocumentPosition(button)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(button.parentElement).toHaveClass("settings-form");
  });

  it("disables the save button with saveDisabled", () => {
    renderForm({ saveDisabled: true });
    expect(screen.getByRole("button", { name: "Save settings" })).toBeDisabled();
  });

  it("hides the save button with showSave={false}", () => {
    renderForm({ showSave: false });
    expect(screen.queryByRole("button", { name: "Save settings" })).not.toBeInTheDocument();
  });
});
