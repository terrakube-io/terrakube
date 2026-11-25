import { Alert } from "antd";
import "./ManagedInCodeBanner.css";
import { useManagedInCode } from "@/modules/organizations/managedInCode";

/**
 * A line on a settings screen of an organization kept in code: an edit made here is reverted by the next apply.
 * `readOnlyHere` says that this screen's fields really are read-only in that mode. A screen that stays editable, such
 * as the organization settings where the mode is turned off, gets the revert wording instead, so the banner never says
 * a form is read-only while its Save button works.
 */
export default function ManagedInCodeBanner({
  orgId,
  readOnlyHere = false,
}: {
  orgId?: string | null;
  readOnlyHere?: boolean;
}) {
  const managed = useManagedInCode(orgId);
  if (!managed) return null;
  return (
    <Alert
      type="info"
      showIcon
      className="managed-in-code-banner"
      message={
        <>
          <strong>Managed by Terraform.</strong>{" "}
          {managed.readOnly && readOnlyHere
            ? "These settings are read-only here."
            : "Edits here are reverted on the next apply."}
          {managed.repository && (
            <>
              {" "}
              <a href={managed.repository} target="_blank" rel="noopener noreferrer">
                Open repository
              </a>
            </>
          )}
        </>
      }
    />
  );
}
