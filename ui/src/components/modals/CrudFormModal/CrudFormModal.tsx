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
  closeIcon?: React.ReactNode;
  confirmLoading?: boolean;
  initialValues?: Record<string, unknown>;
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
  closeIcon,
  confirmLoading,
  initialValues,
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
      closeIcon={closeIcon}
      confirmLoading={confirmLoading}
      onOk={() => {
        form
          .validateFields()
          .then((values) => {
            onSubmit(values);
          })
          .catch((info) => {
            console.log("Validate Failed:", info);
          });
      }}
    >
      <Space style={{ width: "100%" }} orientation="vertical">
        <Form
          name={formName}
          form={form}
          layout="vertical"
          validateMessages={validateMessages}
          initialValues={initialValues}
        >
          {children}
        </Form>
      </Space>
    </Modal>
  );
}
