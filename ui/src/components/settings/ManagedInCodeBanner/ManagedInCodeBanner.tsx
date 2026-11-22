import { Alert } from "antd";
import "./ManagedInCodeBanner.css";
import { useManagedInCode } from "@/modules/organizations/managedInCode";

/** A line on a settings screen of an organization kept in code: an edit made here is reverted by the next apply. */
export default function ManagedInCodeBanner({ orgId }: { orgId?: string | null }) {
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
          {managed.readOnly ? "These settings are read-only here." : "Edits here are reverted on the next apply."}
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
