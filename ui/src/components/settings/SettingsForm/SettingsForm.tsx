import { Button, Form, FormProps } from "antd";
import clsx from "classnames";
import "./SettingsForm.css";

type Props<Values> = Omit<FormProps<Values>, "layout" | "requiredMark" | "children"> & {
  children?: React.ReactNode;
  saveLabel?: string;
  saveDisabled?: boolean;
  saving?: boolean;
  showSave?: boolean;
};

// The settings page column: one 680px vertical form with the save button at the end, on the left.
export default function SettingsForm<Values>({
  children,
  saveLabel = "Save settings",
  saveDisabled,
  saving,
  showSave = true,
  className,
  ...formProps
}: Props<Values>) {
  return (
    <Form {...formProps} layout="vertical" requiredMark={false} className={clsx("settings-form", className)}>
      {children}
      {showSave && (
        <Button type="primary" htmlType="submit" disabled={saveDisabled} loading={saving}>
          {saveLabel}
        </Button>
      )}
    </Form>
  );
}
