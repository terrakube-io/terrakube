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
import { Link, useLocation } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "@/config/actionTypes";
import organizationService from "@/modules/organizations/organizationService";
import { getOrgIdFromPathname, isOrgId } from "@/config/orgId";
import { FlatOrganization } from "@/domain/types";
import { OrganizationSelector } from "@/components/layout/OrganizationSelector";
import { HelpMenu } from "@/components/layout/HelpMenu";
import { UserMenu } from "@/components/layout/UserMenu";
import { getStoredSidebarCollapsed, setStoredSidebarCollapsed } from "./sidebarPreference";
import logo from "@/domain/Home/white_logo.png";
import "./AppSidebar.css";

const { Sider } = Layout;

type Props = {
  organizationName: string;
  setOrganizationName: (name: string) => void;
  organizations: FlatOrganization[];
  onOrgChange: (orgId: string) => void;
  workspaceManageState: boolean;
};

function ensureOrganizationName(
  orgId: string,
  currentOrgName: string,
  setOrgName: (name: string) => void,
  onComplete: () => void
) {
  if (orgId && currentOrgName) {
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
  workspaceManageState,
}: Props) {
  const [collapsed, setCollapsed] = useState(() => getStoredSidebarCollapsed());
  const [defaultSelected, setDefaultSelected] = useState(["organizations"]);
  const location = useLocation();
  const { token } = theme.useToken();
  const params = location.pathname.split("/");
  const orgIdFromUrl = getOrgIdFromPathname(location.pathname);
  const storedOrgId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const organizationId = isOrgId(storedOrgId) ? storedOrgId : orgIdFromUrl;
  const isSettingsContext = orgIdFromUrl !== null && params[3] === "settings";
  const isWorkspaceDetailContext = orgIdFromUrl !== null && params[3] === "workspaces" && isOrgId(params[4]);
  const isWorkspaceSettingsContext = isWorkspaceDetailContext && params[5] === "settings";
  const isUserSettingsContext = params[1] === "settings" && Boolean(params[2]);
  const canCollapse = !isSettingsContext && !isWorkspaceSettingsContext && !isUserSettingsContext;
  const effectiveCollapsed = canCollapse ? collapsed : false;

  useEffect(() => {
    if (organizationId && !sessionStorage.getItem(ORGANIZATION_NAME)) {
      ensureOrganizationName(organizationId, organizationName, setOrganizationName, () => {});
    }
  }, [organizationId, organizationName, setOrganizationName]);

  useEffect(() => {
    organizationService
      .listOrganizationsGraphQL()
      .then((loadedOrganizations: FlatOrganization[]) => {
        if (orgIdFromUrl && !sessionStorage.getItem(ORGANIZATION_NAME)) {
          const foundOrg = loadedOrganizations.find((org) => org.id === orgIdFromUrl);
          if (foundOrg) {
            sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgIdFromUrl);
            sessionStorage.setItem(ORGANIZATION_NAME, foundOrg.name);
            setOrganizationName(foundOrg.name);
          } else {
            ensureOrganizationName(orgIdFromUrl, "", setOrganizationName, () => {});
          }
        } else {
          setOrganizationName(sessionStorage.getItem(ORGANIZATION_NAME) || "");
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

  const handleOrgMenuClick = (key: string) => {
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      setDefaultSelected([key]);
    });
  };

  const orgBasePath = `/organizations/${orgIdFromUrl}`;
  const workspaceBasePath = `${orgBasePath}/workspaces/${params[4]}`;

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
          label: <Link to="/">Home</Link>,
          key: "__back__",
          icon: <LeftOutlined />,
        },
        {
          type: "group" as const,
          key: "account-settings",
          label: "Account Settings",
          children: [
            { key: "tokens", name: "Tokens", icon: <KeyOutlined /> },
            { key: "theme", name: "Theme", icon: <BgColorsOutlined /> },
          ].map((item) => ({
            key: item.key,
            icon: item.icon,
            label: (
              <Link to={`/settings/${item.key}`} onClick={() => setDefaultSelected([item.key])}>
                {item.name}
              </Link>
            ),
          })),
        },
      ]
    : isWorkspaceSettingsContext
      ? [
          {
            label: (
              <Link to={workspaceBasePath} onClick={() => handleOrgMenuClick("overview")}>
                Back to Workspace
              </Link>
            ),
            key: "__back__",
            icon: <LeftOutlined />,
          },
          {
            type: "group" as const,
            key: "workspace-settings",
            label: "Workspace Settings",
            children: workspaceSettingsItems.map((item) => ({
              key: item.key,
              icon: item.icon,
              label: (
                <Link to={`${workspaceBasePath}/settings/${item.path}`} onClick={() => handleOrgMenuClick(item.key)}>
                  {item.label}
                </Link>
              ),
            })),
          },
        ]
      : isSettingsContext
        ? [
            {
              label: (
                <Link to={`${orgBasePath}/workspaces`} onClick={() => handleOrgMenuClick("workspaces")}>
                  Workspaces
                </Link>
              ),
              key: "__back__",
              icon: <LeftOutlined />,
            },
            ...settingsGroups.map((group) => ({
              type: "group" as const,
              key: group.key,
              label: group.label,
              children: group.items.map((item) => ({
                key: item.key,
                icon: item.icon,
                label: (
                  <Link to={`${orgBasePath}/settings/${item.path}`} onClick={() => handleOrgMenuClick(item.key)}>
                    {item.label}
                  </Link>
                ),
              })),
            })),
          ]
        : isWorkspaceDetailContext
          ? [
              {
                label: (
                  <Link to={`${orgBasePath}/workspaces`} onClick={() => handleOrgMenuClick("workspaces")}>
                    Workspaces
                  </Link>
                ),
                key: "__back__",
                icon: <LeftOutlined />,
              },
              {
                key: "overview",
                label: (
                  <Link to={workspaceBasePath} onClick={() => handleOrgMenuClick("overview")}>
                    Overview
                  </Link>
                ),
                icon: <DashboardOutlined />,
              },
              {
                key: "runs",
                label: (
                  <Link to={`${workspaceBasePath}/runs`} onClick={() => handleOrgMenuClick("runs")}>
                    Runs
                  </Link>
                ),
                icon: <HistoryOutlined />,
              },
              {
                key: "states",
                label: workspaceManageState ? (
                  <Link to={`${workspaceBasePath}/states`} onClick={() => handleOrgMenuClick("states")}>
                    States
                  </Link>
                ) : (
                  "States"
                ),
                icon: <DatabaseOutlined />,
                disabled: !workspaceManageState,
              },
              {
                key: "variables",
                label: (
                  <Link to={`${workspaceBasePath}/variables`} onClick={() => handleOrgMenuClick("variables")}>
                    Variables
                  </Link>
                ),
                icon: <CodeOutlined />,
              },
              {
                key: "schedules",
                label: (
                  <Link to={`${workspaceBasePath}/schedules`} onClick={() => handleOrgMenuClick("schedules")}>
                    Schedules
                  </Link>
                ),
                icon: <ScheduleOutlined />,
              },
              {
                key: "settings",
                label: (
                  <Link to={`${workspaceBasePath}/settings/general`} onClick={() => handleOrgMenuClick("settings")}>
                    Settings
                  </Link>
                ),
                icon: <SettingOutlined />,
              },
            ]
          : orgIdFromUrl
            ? [
                { key: "projects", name: "Projects", icon: <ProjectOutlined /> },
                { key: "workspaces", name: "Workspaces", icon: <AppstoreOutlined /> },
                { key: "registry", name: "Registry", icon: <CloudOutlined /> },
                { key: "settings", name: "Settings", icon: <SettingOutlined /> },
              ].map((item) => ({
                key: item.key,
                icon: item.icon,
                label: (
                  <Link to={`${orgBasePath}/${item.key}`} onClick={() => handleOrgMenuClick(item.key)}>
                    {item.name}
                  </Link>
                ),
              }))
            : [
                {
                  label: <Link to="/organizations">Organizations</Link>,
                  key: "organizations",
                  icon: <BankOutlined />,
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
          <Link to="/" className="app-sidebar-home-link">
            <img src={logo} alt="Terrakube" className="app-sidebar-logo" />
          </Link>
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
              placement="top"
            />
          )}
        </div>
      </div>
    </Sider>
  );
}
