import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import { ActionSettings } from "./Actions";
import { GeneralSettings } from "./General";
import { GlobalVariablesSettings } from "./GlobalVariables";
import "./Settings.css";
import { SSHKeysSettings } from "./SSHKeys";
import { AgentSettings } from "./Agents";
import { TagsSettings } from "./Tags";
import { TeamSettings } from "./Teams";
import { FederatedCredentials } from "./FederatedCredentials";
import { OrgNotifications } from "./Notifications";
import { TemplatesSettings } from "./Templates";
import { VCSSettings } from "./VCS";
import { VariableCollectionsSettings } from "./VariableCollections";
import { CreateEditCollection } from "./CreateEditCollection";
import { useOrgPermissions } from "../../modules/permissions/useOrgPermissions";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";

const SETTINGS_TAB_PATHS: Record<string, string> = {
  "1": "general",
  "2": "teams",
  "3": "variables",
  "4": "vcs",
  "5": "templates",
  "6": "ssh",
  "7": "tags",
  "8": "agents",
  "9": "collection",
  "10": "actions",
  "11": "federated-credentials",
  "12": "notifications",
};

const SETTINGS_TAB_LABELS: Record<string, string> = {
  "1": "General",
  "2": "Teams",
  "3": "Global Variables",
  "4": "VCS Providers",
  "5": "Templates",
  "6": "SSH Keys",
  "7": "Tags",
  "8": "Agents",
  "9": "Variable Collections",
  "10": "Actions",
  "11": "Federated Credentials",
  "12": "Notifications",
};

type Props = {
  selectedTab?: string;
  vcsMode?: "new" | "edit" | "list";
  vcsId?: string;
  editorMode?: "new" | "edit";
  editorId?: string;
  collectionMode?: "list" | "new" | "edit" | "detail";
  collectionId?: string;
};

export const OrganizationSettings = ({
  selectedTab,
  vcsMode,
  vcsId,
  editorMode,
  editorId,
  collectionMode = "list",
  collectionId,
}: Props) => {
  const { orgid } = useParams();
  const [activeKey, setActiveKey] = useState(selectedTab || "1");
  const { permissions } = useOrgPermissions();

  useEffect(() => {
    if (selectedTab) {
      setActiveKey(selectedTab);
    }
  }, [selectedTab]);

  // Render appropriate content for Variable Collections tab
  const renderCollectionContent = () => {
    switch (collectionMode) {
      case "new":
        return <CreateEditCollection mode="create" managePermission={permissions.manageCollection} />;
      case "edit":
        return (
          <CreateEditCollection
            mode="edit"
            collectionId={collectionId}
            managePermission={permissions.manageCollection}
          />
        );
      case "list":
      default:
        return <VariableCollectionsSettings managePermission={permissions.manageCollection} />;
    }
  };

  const renderContent = () => {
    switch (activeKey) {
      case "1":
        return <GeneralSettings managePermission={permissions.managePermission} />;
      case "2":
        return (
          <TeamSettings
            key={activeKey}
            editorMode={editorMode}
            editorId={editorId}
            managePermission={permissions.managePermission}
          />
        );
      case "3":
        return <GlobalVariablesSettings managePermission={permissions.managePermission} />;
      case "4":
        return <VCSSettings vcsMode={vcsMode} vcsId={vcsId} managePermission={permissions.manageVcs} />;
      case "5":
        return (
          <TemplatesSettings
            key={activeKey}
            editorMode={editorMode}
            editorId={editorId}
            managePermission={permissions.manageTemplate}
          />
        );
      case "6":
        return <SSHKeysSettings managePermission={permissions.manageVcs} />;
      case "7":
        return <TagsSettings managePermission={permissions.manageWorkspace} />;
      case "8":
        return <AgentSettings managePermission={permissions.managePermission} />;
      case "9":
        return renderCollectionContent();
      case "10":
        return (
          <ActionSettings editorMode={editorMode} editorId={editorId} managePermission={permissions.managePermission} />
        );
      case "11":
        return (
          <FederatedCredentials
            editorMode={editorMode}
            editorId={editorId}
            managePermission={permissions.managePermission}
          />
        );
      case "12":
        return (
          <OrgNotifications
            editorMode={editorMode}
            editorId={editorId}
            managePermission={permissions.managePermission}
          />
        );
      default:
        return <GeneralSettings managePermission={permissions.managePermission} />;
    }
  };

  return (
    <PageWrapper
      title="Organization Settings"
      showTitle={false}
      breadcrumbs={[
        { label: sessionStorage.getItem(ORGANIZATION_NAME) ?? "", path: "/" },
        { label: "Settings", path: `/organizations/${orgid}/settings/general` },
        {
          label: SETTINGS_TAB_LABELS[activeKey] ?? "General",
          ...(editorMode || vcsMode === "new" || vcsMode === "edit" || collectionMode !== "list"
            ? { path: `/organizations/${orgid}/settings/${SETTINGS_TAB_PATHS[activeKey] ?? "general"}` }
            : {}),
        },
        ...(editorMode || vcsMode === "new" || vcsMode === "edit"
          ? [{ label: editorMode === "new" || vcsMode === "new" ? "New" : "Edit" }]
          : []),
        ...(collectionMode === "new" || collectionMode === "edit"
          ? [{ label: collectionMode === "new" ? "New" : "Edit" }]
          : []),
      ]}
    >
      {renderContent()}
    </PageWrapper>
  );
};
