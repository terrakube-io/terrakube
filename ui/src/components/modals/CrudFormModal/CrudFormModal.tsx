import { Button, Flex, Form, Modal } from "antd";
import type { FormInstance } from "antd";
import "./CrudFormModal.css";

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
  const submit = () => {
    // Invalid fields show their own messages, so only validation failures are swallowed.
    form.validateFields().then(onSubmit, (err) => {
      if (!err?.errorFields) throw err;
    });
  };

  return (
    <Modal
      className="form-modal"
      width={width}
      open={open}
      title={title}
      onCancel={onCancel}
      closeIcon={closeIcon}
      footer={
        <Flex gap="small">
          <Button type="primary" loading={confirmLoading} onClick={submit}>
            {okText}
          </Button>
          <Button onClick={onCancel}>Cancel</Button>
        </Flex>
      }
    >
      <Form
        name={formName}
        form={form}
        layout="vertical"
        validateMessages={validateMessages}
        initialValues={initialValues}
      >
        {children}
      </Form>
    </Modal>
  );
}
