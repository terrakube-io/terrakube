import { CheckCircleFilled, CloseCircleOutlined, SafetyOutlined } from "@ant-design/icons";
import { Button, Popover, Typography } from "antd";
import { AccessScope, Capability, accessLevelLabel } from "@/modules/permissions/accessModel";
import "./AccessChip.css";

type Props = {
  scope: AccessScope;
  /** The highest role the person has, or undefined while it is not known: nothing is shown then. */
  level?: string;
  /** The teams that grant the access. */
  teams: string[];
  items: Capability[];
};

const throughLabel = (teams: string[]) => (teams.length === 1 ? `via ${teams[0]}` : `via ${teams.length} teams`);

/**
 * What the person can do here, in the header where the actions are, instead of a banner on every visit. The label is
 * the role; the list says what that role means in this place, so "Custom" is never left unexplained.
 */
export default function AccessChip({ scope, level, teams, items }: Props) {
  if (!level) return null;
  const title = `Your access in this ${scope}`;

  const content = (
    <div className="access-chip-panel">
      <Typography.Text type="secondary">
        {teams.length > 0
          ? `${accessLevelLabel(level)}, granted through ${teams.length === 1 ? "team" : "teams"} ${teams.join(", ")}`
          : accessLevelLabel(level)}
        {teams.length > 1 ? ". You get the highest role across them." : "."}
      </Typography.Text>
      <ul className="access-chip-list">
        {items.map((item) => (
          <li key={item.label} aria-label={`${item.allowed ? "Can" : "Cannot"}: ${item.label}`}>
            {item.allowed ? (
              <CheckCircleFilled className="access-chip-yes" />
            ) : (
              <CloseCircleOutlined className="access-chip-no" />
            )}
            <span className={item.allowed ? undefined : "access-chip-denied"}>{item.label}</span>
          </li>
        ))}
      </ul>
    </div>
  );

  return (
    <Popover trigger="click" placement="bottomRight" title={title} content={content}>
      <Button icon={<SafetyOutlined />} className="access-chip" aria-label={title}>
        {accessLevelLabel(level)}
        {teams.length > 0 && <span className="access-chip-via"> · {throughLabel(teams)}</span>}
      </Button>
    </Popover>
  );
}
