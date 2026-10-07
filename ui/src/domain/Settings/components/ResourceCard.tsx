import { Flex, Typography } from "antd";
import "./ResourceCard.css";

type Props = {
  icon: React.ReactNode;
  name: React.ReactNode;
  tags?: React.ReactNode;
  actions?: React.ReactNode;
  children?: React.ReactNode;
};

// DESIGN.md resource card: icon tile, name with tags, detail lines, icon actions on the right.
export default function ResourceCard({ icon, name, tags, actions, children }: Props) {
  return (
    <div className="resource-card">
      <span className="resource-card-icon" aria-hidden="true">
        {icon}
      </span>
      <div className="resource-card-body">
        <Flex gap="small" align="center" wrap>
          <Typography.Text className="resource-card-name">{name}</Typography.Text>
          {tags}
        </Flex>
        {children}
      </div>
      {actions && <div className="resource-card-actions">{actions}</div>}
    </div>
  );
}
