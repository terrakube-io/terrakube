import { Button, Flex, Input, Modal, Space, Typography } from "antd";
import { useEffect, useState } from "react";
import "../CrudFormModal/CrudFormModal.css";
import "./DeleteConfirmationModal.css";

type Props = {
  open: boolean;
  title: string;
  message: React.ReactNode;
  confirmValue?: string;
  okText?: string;
  confirmLoading?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

export default function DeleteConfirmationModal({
  open,
  title,
  message,
  confirmValue,
  okText = "Delete",
  confirmLoading,
  onConfirm,
  onCancel,
}: Props) {
  const [confirmation, setConfirmation] = useState("");

  // Also when the parent closes it: a reopened dialog must not keep the previous answer.
  useEffect(() => {
    if (!open) setConfirmation("");
  }, [open]);

  const close = (action: () => void) => {
    action();
    setConfirmation("");
  };

  return (
    <Modal
      className="form-modal"
      title={title}
      open={open}
      onCancel={() => close(onCancel)}
      footer={
        <Flex gap="small">
          <Button
            type="primary"
            danger
            loading={confirmLoading}
            disabled={confirmValue !== undefined && confirmation !== confirmValue}
            onClick={() => close(onConfirm)}
          >
            {okText}
          </Button>
          <Button onClick={() => close(onCancel)}>Cancel</Button>
        </Flex>
      }
    >
      <Space orientation="vertical" className="delete-confirmation-body">
        <Typography.Text>{message}</Typography.Text>
        {confirmValue !== undefined && (
          <>
            <Typography.Text>
              Type <Typography.Text strong>{confirmValue}</Typography.Text> to confirm.
            </Typography.Text>
            <Input
              value={confirmation}
              onChange={(e) => setConfirmation(e.target.value)}
              aria-label="Type the name to confirm"
            />
          </>
        )}
      </Space>
    </Modal>
  );
}
