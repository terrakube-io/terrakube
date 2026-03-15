import { Breadcrumb, Layout, Menu, Tag, theme } from "antd";
import { useEffect, useState } from "react";
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
import { TemplatesSettings } from "./Templates";
import { VCSSettings } from "./VCS";
import { VariableCollectionsSettings } from "./VariableCollections";
import { CreateEditCollection } from "./CreateEditCollection";
import { useNavigate, useParams, useLocation } from "react-router-dom";
import type { MenuProps } from "antd";
import { useOrgPermissions } from "../../modules/permissions/useOrgPermissions";

const { Content, Sider } = Layout;
type MenuItem = Required<MenuProps>["items"][number];

type Props = {
  selectedTab?: string;
  vcsMode?: "new" | "list";
  collectionMode?: "list" | "new" | "edit" | "detail";
  collectionId?: string;
};

export const OrganizationSettings = ({ selectedTab, vcsMode, collectionMode = "list", collectionId }: Props) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [activeKey, setActiveKey] = useState(selectedTab || "1");
  const { token } = theme.useToken();
  const { permissions, loading: permissionsLoading } = useOrgPermissions();

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
      default:
        return <GeneralSettings managePermission={permissions.managePermission} />;
    }
  };

  const handleMenuClick: MenuProps["onClick"] = (e) => {
    setActiveKey(e.key);

    // Special case for collection tab
    if (e.key === "9" && location.pathname.includes("/collection/")) {
      navigate(`/organizations/${orgid}/settings/collection`);
    }
  };

  const menuItems: MenuItem[] = [
    {
      type: "group",
      label: "Organization Settings",
      key: "org-settings",
      children: [
        { key: "1", label: "General" },
        { key: "2", label: "Teams" },
        { key: "7", label: "Tags" },
        { key: "3", label: "Global Variables" },
        { key: "9", label: "Variable Collections" },
      ],
    },
    {
      type: "group",
      label: "Version Control",
      key: "version-control",
      children: [
        { key: "4", label: "VCS Providers" },
        { key: "6", label: "SSH Keys" },
      ],
    },
    {
      type: "group",
      label: "Security",
      key: "security",
      children: [{ key: "8", label: "Agents" }, { key: "11", label: "Federated Credentials" }],
    },
    {
      type: "group",
      label: "Integrations",
      key: "integrations",
      children: [
        { key: "5", label: "Templates" },
        {
          key: "10",
          label: (
            <>
              Actions <Tag color={token.colorPrimary}>beta</Tag>
            </>
          ),
        },
      ],
    },
  ];

  return (
    <Content style={{ padding: "0 50px", background: token.colorBgContainer }}>
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: sessionStorage.getItem(ORGANIZATION_NAME),
          },
          {
            title: "Settings",
          },
        ]}
      />

      <div className="site-layout-content" style={{ background: token.colorBgContainer, paddingLeft: "0" }}>
        <Layout style={{ background: token.colorBgContainer }}>
          <Sider
            width={200}
            style={{
              background: token.colorBgContainer,
              borderRight: `1px solid ${token.colorBorderSecondary}`,
              height: "100%",
              overflow: "auto",
            }}
          >
            <Menu
              mode="inline"
              selectedKeys={[activeKey]}
              style={{ height: "100%" }}
              items={menuItems}
              onClick={handleMenuClick}
            />
          </Sider>
          <Content style={{ padding: "0 24px", minHeight: 280 }}>{renderContent()}</Content>
        </Layout>
      </div>
    </Content>
  );
};
