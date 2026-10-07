import { Empty } from "antd";
import "./EmptyState.css";

type Props = {
  description: React.ReactNode;
  /** @deprecated Every empty state uses the simple illustration now. */
  simple?: boolean;
  children?: React.ReactNode;
};

// One illustration everywhere: the detailed antd picture has fixed greys that clash with the dark theme.
export default function EmptyState({ description, children }: Props) {
  return (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} className="empty-state">
      {children}
    </Empty>
  );
}
