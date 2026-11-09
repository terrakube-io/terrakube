import { Tooltip } from "antd";
import type { ReactElement } from "react";

type Props = {
  disabled: boolean;
  /** Plain words for what is missing, for example "You need Manage Workspaces to create projects." */
  reason: string;
  children: ReactElement;
};

/**
 * Explains a disabled control. A button that is greyed out with no explanation sends people to ask someone, so the
 * reason is shown on hover and focus. Enabled controls are returned untouched.
 */
export default function DisabledReason({ disabled, reason, children }: Props) {
  if (!disabled) return children;
  return (
    <Tooltip title={reason} trigger={["hover", "focus"]}>
      {/* The wrapper takes the hover, because browsers send no pointer events from a disabled button, and the focus,
          so the reason is reachable without a pointer. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
      <span className="disabled-reason" role="group" tabIndex={0} aria-label={reason}>
        {children}
      </span>
    </Tooltip>
  );
}
