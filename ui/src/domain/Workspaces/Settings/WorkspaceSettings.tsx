import { Layout, Menu, theme } from "antd";
import { useState } from "react";
import { WorkspaceGeneral } from "./General";
import { WorkspaceLocking } from "./Locking";
import { WorkspaceSSHKey } from "./SSHKey";
import { WorkspaceWebhook } from "./Webhook";
import { WorkspaceAdvanced } from "./Advanced";
import { Workspace, Template, VcsType } from "../../types";
import type { MenuProps } from "antd";

const { Content, Sider } = Layout;
type MenuItem = Required<MenuProps>["items"][number];

type Props = {
  workspace: Workspace;
  orgTemplates: Template[];
  manageWorkspace: boolean;
  vcsProvider?: VcsType;
  onWorkspaceUpdate?: () => void;
};

export const WorkspaceSettings = ({
  workspace,
  orgTemplates,
  manageWorkspace,
  vcsProvider,
  onWorkspaceUpdate,
}: Props) => {
  const [activeKey, setActiveKey] = useState("general");
  const { token } = theme.useToken();

  const handleMenuClick: MenuProps["onClick"] = (e) => {
    setActiveKey(e.key);
  };

  const handleWorkspaceUpdate = () => {
    if (onWorkspaceUpdate) {
      onWorkspaceUpdate();
    }
  };

  const renderContent = () => {
    switch (activeKey) {
      case "general":
        return (
          <WorkspaceGeneral workspaceData={workspace} orgTemplates={orgTemplates} manageWorkspace={manageWorkspace} />
        );
      case "locking":
        return (
          <WorkspaceLocking
            workspace={workspace}
            manageWorkspace={manageWorkspace}
            onWorkspaceUpdate={handleWorkspaceUpdate}
          />
        );
      case "sshkey":
        return <WorkspaceSSHKey workspace={workspace} manageWorkspace={manageWorkspace} />;
      case "webhook":
        return (
          <WorkspaceWebhook
            workspace={workspace}
            vcsProvider={vcsProvider}
            orgTemplates={orgTemplates}
            manageWorkspace={manageWorkspace}
          />
        );
      case "advanced":
        return <WorkspaceAdvanced workspace={workspace} manageWorkspace={manageWorkspace} />;
      default:
        return (
          <WorkspaceGeneral workspaceData={workspace} orgTemplates={orgTemplates} manageWorkspace={manageWorkspace} />
        );
    }
  };

  const menuItems: MenuItem[] = [
    {
      type: "group",
      label: "Workspace Settings",
      key: "workspace-settings",
      children: [
        { key: "general", label: "General" },
        { key: "locking", label: "Locking" },
        { key: "sshkey", label: "SSH Key" },
        { key: "webhook", label: "Webhook" },
        { key: "advanced", label: "Destruction and Deletion" },
      ],
    },
  ];

  return (
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
      <Content style={{ padding: "0 24px", minHeight: 280, maxWidth: 900 }}>{renderContent()}</Content>
    </Layout>
  );
};
