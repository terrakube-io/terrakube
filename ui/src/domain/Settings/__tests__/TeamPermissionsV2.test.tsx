import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Form } from "antd";
import { useEffect } from "react";
import { TeamPermissionsV2 } from "../TeamPermissionsV2";

const Wrapper = ({ initialRole }: { initialRole: string }) => {
  const [form] = Form.useForm();
  // Like EditTeam: the loaded role is set after the fields mount.
  useEffect(() => form.setFieldsValue({ role: initialRole }), [form, initialRole]);
  return (
    <Form form={form}>
      <TeamPermissionsV2 managePermissions={true} />
    </Form>
  );
};

describe("TeamPermissionsV2", () => {
  it("shows a preset role's permissions as checked, read-only boxes", async () => {
    render(<Wrapper initialRole="plan" />);

    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Plan runs" })).toBeChecked());
    expect(screen.getByRole("checkbox", { name: "Plan runs" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "Apply runs" })).not.toBeChecked();
    expect(screen.getByText("Set by the Plan role. Choose Custom to change them.")).toBeInTheDocument();
  });

  it("lets a custom role pick individual permissions", () => {
    render(<Wrapper initialRole="custom" />);

    const apply = screen.getByRole("checkbox", { name: "Apply runs" });
    expect(apply).toBeEnabled();
    fireEvent.click(apply);
    expect(apply).toBeChecked();
  });

  it("switching to a preset role applies its permissions", async () => {
    render(<Wrapper initialRole="custom" />);

    fireEvent.click(screen.getByRole("radio", { name: /^Admin/ }));

    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Manage modules" })).toBeChecked());
    expect(screen.getByRole("checkbox", { name: "Manage modules" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "Manage policies" })).toBeChecked();
  });
});
