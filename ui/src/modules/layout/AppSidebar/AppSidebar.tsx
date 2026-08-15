import {
  ApiOutlined,
  AppstoreOutlined,
  BankOutlined,
  BellOutlined,
  BgColorsOutlined,
  BranchesOutlined,
  CloudOutlined,
  CodeOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  DoubleLeftOutlined,
  DoubleRightOutlined,
  FileTextOutlined,
  FolderOutlined,
  HistoryOutlined,
  KeyOutlined,
  LeftOutlined,
  LockOutlined,
  ProjectOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
  SettingOutlined,
  ShareAltOutlined,
  TagsOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import { Layout, Menu, Tag, theme } from "antd";
import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "@/config/actionTypes";
import organizationService from "@/modules/organizations/organizationService";
import { FlatOrganization } from "@/domain/types";
import { OrganizationSelector } from "@/components/OrganizationSelector";
import { HelpMenu } from "@/components/HelpMenu";
import { UserMenu } from "@/components/UserMenu";
import { getStoredSidebarCollapsed, setStoredSidebarCollapsed } from "./sidebarPreference";
import logo from "@/domain/Home/white_logo.png";
import "./AppSidebar.css";

const { Sider } = Layout;

type Props = {
  organizationName: string;
  setOrganizationName: (name: string) => void;
  organizations: FlatOrganization[];
  onOrgChange: (orgId: string) => void;
  onManageOrgs: () => void;
  workspaceManageState: boolean;
};

function ensureOrganizationName(
  orgId: string,
  currentOrgName: string,
  setOrgName: (name: string) => void,
  onComplete: () => void
) {
  if (orgId && currentOrgName && currentOrgName !== "select organization") {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgId);
    sessionStorage.setItem(ORGANIZATION_NAME, currentOrgName);
    onComplete();
  } else {
    organizationService
      .getOrganizationNameGraphQL(orgId)
      .then((orgName) => {
        if (orgName) {
          sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgId);
          sessionStorage.setItem(ORGANIZATION_NAME, orgName);
          setOrgName(orgName);
          onComplete();
        }
      })
      .catch((error) => {
        console.error("Failed to fetch organization:", error);
      });
  }
}

export default function AppSidebar({
  organizationName,
  setOrganizationName,
  organizations,
  onOrgChange,
  onManageOrgs,
  workspaceManageState,
}: Props) {
  const [collapsed, setCollapsed] = useState(() => getStoredSidebarCollapsed());
  const [defaultSelected, setDefaultSelected] = useState(["organizations"]);
  const location = useLocation();
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const params = location.pathname.split("/");
  const orgIdFromUrl = params.length > 2 && params[1] === "organizations" && params[2] !== "create" ? params[2] : null;
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE) || orgIdFromUrl;
  const isSettingsContext = orgIdFromUrl !== null && params[3] === "settings";
  const isWorkspaceDetailContext = orgIdFromUrl !== null && params[3] === "workspaces" && Boolean(params[4]);
  const isWorkspaceSettingsContext = isWorkspaceDetailContext && params[5] === "settings";
  const isUserSettingsContext = params[1] === "settings" && Boolean(params[2]);
  const canCollapse = !isSettingsContext && !isWorkspaceSettingsContext && !isUserSettingsContext;
  const effectiveCollapsed = canCollapse ? collapsed : false;

  useEffect(() => {
    if (organizationId && (!sessionStorage.getItem(ORGANIZATION_NAME) || organizationName === "select organization")) {
      ensureOrganizationName(organizationId, organizationName, setOrganizationName, () => {});
    }
  }, [organizationId, organizationName, setOrganizationName]);

  useEffect(() => {
    organizationService
      .listOrganizationsGraphQL()
      .then((loadedOrganizations: FlatOrganization[]) => {
        if (
          orgIdFromUrl &&
          (!sessionStorage.getItem(ORGANIZATION_NAME) || organizationName === "select organization")
        ) {
          const foundOrg = loadedOrganizations.find((org) => org.id === orgIdFromUrl);
          if (foundOrg) {
            sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgIdFromUrl);
            sessionStorage.setItem(ORGANIZATION_NAME, foundOrg.name);
            setOrganizationName(foundOrg.name);
          } else {
            ensureOrganizationName(orgIdFromUrl, "", setOrganizationName, () => {});
          }
        } else {
          setOrganizationName(sessionStorage.getItem(ORGANIZATION_NAME) || "select organization");
        }
      })
      .catch((error) => {
        console.error("Failed to load organizations:", error);
      });

    if (isUserSettingsContext) {
      setDefaultSelected([params[2]]);
    } else if (isWorkspaceSettingsContext) {
      setDefaultSelected([params[6] || "general"]);
    } else if (isSettingsContext) {
      setDefaultSelected([params[4] || "general"]);
    } else if (isWorkspaceDetailContext) {
      setDefaultSelected([params[5] || "overview"]);
    } else if (location.pathname.includes("registry")) {
      setDefaultSelected(["registry"]);
    } else if (location.pathname.includes("projects")) {
      setDefaultSelected(["projects"]);
    } else if (orgIdFromUrl) {
      setDefaultSelected(["workspaces"]);
    } else {
      setDefaultSelected(["organizations"]);
    }
  }, [
    orgIdFromUrl,
    location.pathname,
    setOrganizationName,
    isSettingsContext,
    isWorkspaceDetailContext,
    isWorkspaceSettingsContext,
    isUserSettingsContext,
  ]);

  const handleSectionNavigation = (section: string) => {
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      navigate(`/organizations/${orgIdFromUrl}/${section}`);
      setDefaultSelected([section]);
    });
  };

  const handleSettingsNavigation = (key: string, path: string) => {
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      navigate(`/organizations/${orgIdFromUrl}/settings/${path}`);
      setDefaultSelected([key]);
    });
  };

  const handleBackToWorkspaces = () => {
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      navigate(`/organizations/${orgIdFromUrl}/workspaces`);
      setDefaultSelected(["workspaces"]);
    });
  };

  const handleBackToWorkspace = () => {
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      navigate(`/organizations/${orgIdFromUrl}/workspaces/${params[4]}`);
      setDefaultSelected(["overview"]);
    });
  };

  const handleWorkspaceSectionNavigation = (key: string, path: string) => {
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      navigate(`/organizations/${orgIdFromUrl}/workspaces/${params[4]}${path ? `/${path}` : ""}`);
      setDefaultSelected([key]);
    });
  };

  const handleWorkspaceSettingsNavigation = (key: string, path: string) => {
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      navigate(`/organizations/${orgIdFromUrl}/workspaces/${params[4]}/settings/${path}`);
      setDefaultSelected([key]);
    });
  };

  const handleUserSettingsNavigation = (key: string, path: string) => {
    navigate(`/settings/${path}`);
    setDefaultSelected([key]);
  };

  const settingsGroups = [
    {
      key: "org-settings",
      label: "Organization Settings",
      items: [
        { key: "general", label: "General", path: "general", icon: <SettingOutlined /> },
        { key: "teams", label: "Teams", path: "teams", icon: <TeamOutlined /> },
        { key: "tags", label: "Tags", path: "tags", icon: <TagsOutlined /> },
        { key: "variables", label: "Global Variables", path: "variables", icon: <CodeOutlined /> },
        { key: "collection", label: "Variable Collections", path: "collection", icon: <FolderOutlined /> },
      ],
    },
    {
      key: "version-control",
      label: "Version Control",
      items: [
        { key: "vcs", label: "VCS Providers", path: "vcs", icon: <BranchesOutlined /> },
        { key: "ssh", label: "SSH Keys", path: "ssh", icon: <KeyOutlined /> },
      ],
    },
    {
      key: "security",
      label: "Security",
      items: [
        { key: "agents", label: "Agents", path: "agents", icon: <RobotOutlined /> },
        {
          key: "federated-credentials",
          label: "Federated Credentials",
          path: "federated-credentials",
          icon: <SafetyCertificateOutlined />,
        },
      ],
    },
    {
      key: "integrations",
      label: "Integrations",
      items: [
        { key: "templates", label: "Templates", path: "templates", icon: <FileTextOutlined /> },
        {
          key: "actions",
          label: (
            <>
              Actions <Tag color={token.colorPrimary}>beta</Tag>
            </>
          ),
          path: "actions",
          icon: <ThunderboltOutlined />,
        },
        { key: "notifications", label: "Notifications", path: "notifications", icon: <BellOutlined /> },
      ],
    },
  ];

  const workspaceSettingsItems = [
    { key: "general", label: "General", path: "general", icon: <SettingOutlined /> },
    { key: "locking", label: "Locking", path: "locking", icon: <LockOutlined /> },
    { key: "sshkey", label: "SSH Key", path: "sshkey", icon: <KeyOutlined /> },
    { key: "webhook", label: "Webhook", path: "webhook", icon: <ApiOutlined /> },
    { key: "notifications", label: "Notifications", path: "notifications", icon: <BellOutlined /> },
    { key: "state-shared", label: "State Shared", path: "state-shared", icon: <ShareAltOutlined /> },
    { key: "team-access", label: "Team Access", path: "team-access", icon: <TeamOutlined /> },
    { key: "advanced", label: "Destruction and Deletion", path: "advanced", icon: <DeleteOutlined /> },
  ];

  const items = isUserSettingsContext
    ? [
        {
          label: "Home",
          key: "__back__",
          icon: <LeftOutlined />,
          onClick: () => navigate("/"),
        },
        {
          type: "group" as const,
          key: "account-settings",
          label: "Account Settings",
          children: [
            { key: "tokens", label: "Tokens", icon: <KeyOutlined /> },
            { key: "theme", label: "Theme", icon: <BgColorsOutlined /> },
          ].map((item) => ({
            ...item,
            onClick: () => handleUserSettingsNavigation(item.key, item.key),
          })),
        },
      ]
    : isWorkspaceSettingsContext
      ? [
          {
            label: "Back to Workspace",
            key: "__back__",
            icon: <LeftOutlined />,
            onClick: handleBackToWorkspace,
          },
          {
            type: "group" as const,
            key: "workspace-settings",
            label: "Workspace Settings",
            children: workspaceSettingsItems.map((item) => ({
              key: item.key,
              label: item.label,
              icon: item.icon,
              onClick: () => handleWorkspaceSettingsNavigation(item.key, item.path),
            })),
          },
        ]
      : isSettingsContext
        ? [
            {
              label: "Workspaces",
              key: "__back__",
              icon: <LeftOutlined />,
              onClick: handleBackToWorkspaces,
            },
            ...settingsGroups.map((group) => ({
              type: "group" as const,
              key: group.key,
              label: group.label,
              children: group.items.map((item) => ({
                key: item.key,
                label: item.label,
                icon: item.icon,
                onClick: () => handleSettingsNavigation(item.key, item.path),
              })),
            })),
          ]
        : isWorkspaceDetailContext
          ? [
              {
                label: "Workspaces",
                key: "__back__",
                icon: <LeftOutlined />,
                onClick: handleBackToWorkspaces,
              },
              {
                key: "overview",
                label: "Overview",
                icon: <DashboardOutlined />,
                onClick: () => handleWorkspaceSectionNavigation("overview", ""),
              },
              {
                key: "runs",
                label: "Runs",
                icon: <HistoryOutlined />,
                onClick: () => handleWorkspaceSectionNavigation("runs", "runs"),
              },
              {
                key: "states",
                label: "States",
                icon: <DatabaseOutlined />,
                disabled: !workspaceManageState,
                onClick: () => handleWorkspaceSectionNavigation("states", "states"),
              },
              {
                key: "variables",
                label: "Variables",
                icon: <CodeOutlined />,
                onClick: () => handleWorkspaceSectionNavigation("variables", "variables"),
              },
              {
                key: "schedules",
                label: "Schedules",
                icon: <ScheduleOutlined />,
                onClick: () => handleWorkspaceSectionNavigation("schedules", "schedules"),
              },
              {
                key: "settings",
                label: "Settings",
                icon: <SettingOutlined />,
                onClick: () => handleWorkspaceSectionNavigation("settings", "settings/general"),
              },
            ]
          : orgIdFromUrl
            ? [
                {
                  label: "Projects",
                  key: "projects",
                  icon: <ProjectOutlined />,
                  onClick: () => handleSectionNavigation("projects"),
                },
                {
                  label: "Workspaces",
                  key: "workspaces",
                  icon: <AppstoreOutlined />,
                  onClick: () => handleSectionNavigation("workspaces"),
                },
                {
                  label: "Registry",
                  key: "registry",
                  icon: <CloudOutlined />,
                  onClick: () => handleSectionNavigation("registry"),
                },
                {
                  label: "Settings",
                  key: "settings",
                  icon: <SettingOutlined />,
                  onClick: () => handleSectionNavigation("settings"),
                },
              ]
            : [
                {
                  label: "Organizations",
                  key: "organizations",
                  icon: <BankOutlined />,
                  onClick: () => navigate("/organizations"),
                },
              ];

  const handleToggleCollapsed = () => {
    const next = !collapsed;
    setCollapsed(next);
    setStoredSidebarCollapsed(next);
  };

  return (
    <Sider
      theme="dark"
      width={240}
      collapsedWidth={64}
      collapsed={effectiveCollapsed}
      trigger={null}
      className="app-sidebar"
    >
      <div className="app-sidebar-inner">
        <div className={`app-sidebar-header ${effectiveCollapsed ? "app-sidebar-header--collapsed" : ""}`}>
          <button type="button" className="app-sidebar-home-link" onClick={() => navigate("/")}>
            <img src={logo} alt="Terrakube" className="app-sidebar-logo" />
          </button>
          <div className="app-sidebar-header-actions">
            {!effectiveCollapsed && (
              <div className="app-sidebar-utility">
                <HelpMenu />
                <UserMenu />
              </div>
            )}
            {canCollapse && (
              <button
                type="button"
                className="app-sidebar-collapse-trigger"
                aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
                onClick={handleToggleCollapsed}
              >
                {collapsed ? <DoubleRightOutlined /> : <DoubleLeftOutlined />}
              </button>
            )}
          </div>
        </div>
        <Menu
          key={
            isUserSettingsContext
              ? "user-settings"
              : isWorkspaceSettingsContext
                ? "workspace-settings"
                : isSettingsContext
                  ? "settings"
                  : isWorkspaceDetailContext
                    ? "workspace"
                    : orgIdFromUrl
                      ? "org"
                      : "root"
          }
          mode="inline"
          theme="dark"
          inlineCollapsed={effectiveCollapsed}
          selectedKeys={defaultSelected}
          items={items}
          className="app-sidebar-menu"
        />
        <div className="app-sidebar-footer">
          {!effectiveCollapsed && (
            <OrganizationSelector
              organizationName={organizationName}
              organizations={organizations}
              onOrgChange={onOrgChange}
              onManageOrgs={onManageOrgs}
              placement="top"
            />
          )}
        </div>
      </div>
    </Sider>
  );
}
