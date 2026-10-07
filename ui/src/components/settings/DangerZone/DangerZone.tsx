import { Button, Typography } from "antd";
import { useState } from "react";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import "./DangerZone.css";

type Props = {
  title?: string;
  /** Section level by default; 3 when the danger zone is the whole page. */
  titleLevel?: 3 | 4;
  actionName: string;
  description: React.ReactNode;
  buttonLabel?: string;
  disabled?: boolean;
  onConfirm: () => void;
  confirmValue?: string;
  confirmMessage: React.ReactNode;
  confirmTitle?: string;
};

// The "Destruction and deletion" section: a solid red action, always confirmed.
export default function DangerZone({
  title = "Destruction and deletion",
  titleLevel = 4,
  actionName,
  description,
  buttonLabel = actionName,
  disabled,
  onConfirm,
  confirmValue,
  confirmMessage,
  confirmTitle = actionName,
}: Props) {
  const [open, setOpen] = useState(false);

  return (
    <section className="danger-zone">
      <Typography.Title level={titleLevel}>{title}</Typography.Title>
      <Typography.Text strong>{actionName}</Typography.Text>
      <Typography.Paragraph type="secondary">{description}</Typography.Paragraph>
      <Button type="primary" danger disabled={disabled} onClick={() => setOpen(true)}>
        {buttonLabel}
      </Button>
      <DeleteConfirmationModal
        open={open}
        title={confirmTitle}
        message={confirmMessage}
        confirmValue={confirmValue}
        okText={buttonLabel}
        onConfirm={() => {
          onConfirm();
          setOpen(false);
        }}
        onCancel={() => setOpen(false)}
      />
    </section>
  );
}
