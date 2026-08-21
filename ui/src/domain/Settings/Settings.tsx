import { Breadcrumb, Layout } from "antd";
import { useEffect, useState } from "react";
import { NavLink, useParams } from "react-router-dom";
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

const { Content } = Layout;

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
  vcsMode?: "new" | "list";
  collectionMode?: "list" | "new" | "edit" | "detail";
  collectionId?: string;
};

export const OrganizationSettings = ({ selectedTab, vcsMode, collectionMode = "list", collectionId }: Props) => {
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
        return <TeamSettings key={activeKey} managePermission={permissions.managePermission} />;
      case "3":
        return <GlobalVariablesSettings managePermission={permissions.managePermission} />;
      case "4":
        return <VCSSettings vcsMode={vcsMode} managePermission={permissions.manageVcs} />;
      case "5":
        return <TemplatesSettings key={activeKey} managePermission={permissions.manageTemplate} />;
      case "6":
        return <SSHKeysSettings managePermission={permissions.manageVcs} />;
      case "7":
        return <TagsSettings managePermission={permissions.manageWorkspace} />;
      case "8":
        return <AgentSettings managePermission={permissions.managePermission} />;
      case "9":
        return renderCollectionContent();
      case "10":
        return <ActionSettings managePermission={permissions.managePermission} />;
      case "11":
        return <FederatedCredentials managePermission={permissions.managePermission} />;
      case "12":
        return <OrgNotifications managePermission={permissions.managePermission} />;
      default:
        return <GeneralSettings managePermission={permissions.managePermission} />;
    }
  };

  return (
    <Content style={{ padding: "0 50px" }}>
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: (
              <NavLink to={`/organizations/${orgid}/workspaces`}>{sessionStorage.getItem(ORGANIZATION_NAME)}</NavLink>
            ),
          },
          {
            title: <NavLink to={`/organizations/${orgid}/settings/general`}>Settings</NavLink>,
          },
          {
            title: SETTINGS_TAB_LABELS[activeKey] ?? "General",
          },
        ]}
      />

      <div className="site-layout-content">{renderContent()}</div>
    </Content>
  );
};
