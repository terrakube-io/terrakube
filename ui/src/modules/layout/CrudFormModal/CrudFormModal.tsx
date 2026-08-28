import { Form, Modal, Space } from "antd";
import type { FormInstance } from "antd";

type Props<T> = {
  open: boolean;
  title: React.ReactNode;
  okText: string;
  form: FormInstance<T>;
  formName: string;
  onCancel: () => void;
  onSubmit: (values: T) => void;
  width?: string | number;
  validateMessages?: Record<string, unknown>;
  children: React.ReactNode;
};

export default function CrudFormModal<T>({
  open,
  title,
  okText,
  form,
  formName,
  onCancel,
  onSubmit,
  width = "600px",
  validateMessages,
  children,
}: Props<T>) {
  return (
    <Modal
      width={width}
      open={open}
      title={title}
      okText={okText}
      onCancel={onCancel}
      cancelText="Cancel"
      onOk={() => {
        form.validateFields().then((values) => {
          onSubmit(values);
        });
      }}
    >
      <Space style={{ width: "100%" }} direction="vertical">
        <Form name={formName} form={form} layout="vertical" validateMessages={validateMessages}>
          {children}
        </Form>
      </Space>
    </Modal>
  );
}
