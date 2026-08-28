import { Empty } from "antd";

type Props = {
  description: React.ReactNode;
  simple?: boolean;
  children?: React.ReactNode;
};

export default function EmptyState({ description, simple, children }: Props) {
  return (
    <Empty
      image={simple ? Empty.PRESENTED_IMAGE_SIMPLE : undefined}
      description={description}
      style={{ margin: "96px auto 48px", maxWidth: 420 }}
    >
      {children}
    </Empty>
  );
}
