import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import "../Settings/Settings.css";
import { RunTriggerEventsTable } from "./RunTriggerEventsTable";

/**
 * Instance-wide queue of run trigger events that exhausted their retries, across every organization.
 * Only instance admins can load it; an organization's own events are under its Operations settings.
 */
export const FailedRunTriggerEvents = () => (
  <PageWrapper
    title="Run trigger events"
    showTitle={false}
    breadcrumbs={[{ label: "Instance administration" }, { label: "Run trigger events" }]}
  >
    <div className="setting">
      <SettingsPageHeader
        title="Run trigger events"
        description="Events from every organization that kept failing after repeated attempts. Replaying one tries it again immediately."
        divider={false}
      />
      <RunTriggerEventsTable canReplay />
    </div>
  </PageWrapper>
);

export default FailedRunTriggerEvents;
