import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { getCascadeCapabilities } from "@/modules/cascades/cascadeService";
import { RunTriggerEventsTable } from "../Admin/RunTriggerEventsTable";
import "./Settings.css";
import "./TeamsTagsVariables.css";

/** Failed run trigger events for this organization, with replay for teams that can manage jobs. */
export const RunTriggerEventsSettings = () => {
  const { orgid } = useParams();
  const [canReplay, setCanReplay] = useState(false);

  useEffect(() => {
    let active = true;
    getCascadeCapabilities(orgid)
      .then(({ operate }) => active && setCanReplay(operate))
      .catch(() => active && setCanReplay(false));
    return () => {
      active = false;
    };
  }, [orgid]);

  return (
    <div className="setting">
      <SettingsPageHeader
        title="Run trigger events"
        description="Workspace dependency events that kept failing after repeated attempts. Replaying one tries it again immediately."
        divider={false}
      />
      <RunTriggerEventsTable organizationId={orgid} canReplay={canReplay} />
    </div>
  );
};
