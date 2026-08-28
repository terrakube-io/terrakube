import { Input, Modal, Space, Typography } from "antd";
import { useState } from "react";

type Props = {
  open: boolean;
  title: string;
  message: React.ReactNode;
  confirmValue: string;
  okText?: string;
  onConfirm: () => void;
  onCancel: () => void;
};

export default function DeleteConfirmationModal({
  open,
  title,
  message,
  confirmValue,
  okText = "Delete",
  onConfirm,
  onCancel,
}: Props) {
  const [confirmation, setConfirmation] = useState("");

  const close = (action: () => void) => {
    action();
    setConfirmation("");
  };

  return (
    <Modal
      title={title}
      open={open}
      okText={okText}
      okButtonProps={{ danger: true, disabled: confirmation !== confirmValue }}
      onOk={() => close(onConfirm)}
      onCancel={() => close(onCancel)}
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Typography.Text>{message}</Typography.Text>
        <Typography.Text>
          Type <Typography.Text strong>{confirmValue}</Typography.Text> to confirm.
        </Typography.Text>
        <Input value={confirmation} onChange={(e) => setConfirmation(e.target.value)} placeholder={confirmValue} />
      </Space>
    </Modal>
  );
}
