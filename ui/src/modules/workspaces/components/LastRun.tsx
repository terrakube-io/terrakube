import { Tooltip } from "antd";
import { ClockCircleOutlined } from "@ant-design/icons";
import { formatDateTimeWithZone, relativeTime } from "@/modules/utils/dates";
import "./LastRun.css";

type Props = {
  /** `lastJobDate` of the workspace, the same value the organization list shows. */
  lastRun?: string | null;
};

/** The relative time of a workspace's last run, with the exact time and timezone on hover and keyboard focus. */
export default function LastRun({ lastRun }: Props) {
  const relative = relativeTime(lastRun);

  return (
    <span className="workspace-last-run-line">
      <ClockCircleOutlined aria-hidden />
      {!lastRun || !relative ? (
        <span>Never executed</span>
      ) : (
        <Tooltip title={formatDateTimeWithZone(lastRun)} trigger={["hover", "focus"]}>
          {/* The exact time has to be reachable without a pointer, so this text takes focus. */}
          {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
          <time className="workspace-last-run" dateTime={lastRun} tabIndex={0}>
            {relative}
          </time>
        </Tooltip>
      )}
    </span>
  );
}
