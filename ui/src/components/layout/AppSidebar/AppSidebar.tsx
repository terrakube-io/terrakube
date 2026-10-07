import {
  ApartmentOutlined,
  ApiOutlined,
  AppstoreOutlined,
  BankOutlined,
  BellOutlined,
  BgColorsOutlined,
  BranchesOutlined,
  ClusterOutlined,
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
  MenuOutlined,
  NodeIndexOutlined,
  ProjectOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
  SecurityScanOutlined,
  SettingOutlined,
  ShareAltOutlined,
  TagsOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import { Layout, Menu, Tag } from "antd";
import { useEffect, useRef, useState } from "react";
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

// Same query antd's Sider uses for breakpoint="md"; read synchronously so a phone
// never paints the desktop sidebar first.
const MOBILE_QUERY = "(max-width: 767.98px)";

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
  const [isMobile, setIsMobile] = useState(() => window.matchMedia?.(MOBILE_QUERY).matches ?? false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const openButtonRef = useRef<HTMLButtonElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const siderRef = useRef<HTMLDivElement>(null);
  const wasMobileOpen = useRef(false);
  const [defaultSelected, setDefaultSelected] = useState(["organizations"]);
  const location = useLocation();
  const params = location.pathname.split("/");
  const orgIdFromUrl = getOrgIdFromPathname(location.pathname);
  const storedOrgId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const organizationId = isOrgId(storedOrgId) ? storedOrgId : orgIdFromUrl;
  const isSettingsContext = orgIdFromUrl !== null && params[3] === "settings";
  const isWorkspaceDetailContext = orgIdFromUrl !== null && params[3] === "workspaces" && isOrgId(params[4]);
  const isWorkspaceSettingsContext = isWorkspaceDetailContext && params[5] === "settings";
  const isUserSettingsContext = params[1] === "settings" && Boolean(params[2]);
  // Below md the sidebar is an off-canvas drawer, always shown expanded; elsewhere it collapses on every page.
  const canCollapse = !isMobile;
  const effectiveCollapsed = canCollapse ? collapsed : false;

  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname, isMobile]);

  useEffect(() => {
    if (!mobileOpen) {
      // Every way of closing (Escape, backdrop, close button, choosing a page) returns focus to the toggle.
      // Growing to desktop removes the toggle, so focus moves to the first sidebar link instead of body.
      if (wasMobileOpen.current) {
        (openButtonRef.current ?? siderRef.current?.querySelector<HTMLElement>("a[href]"))?.focus();
      }
      wasMobileOpen.current = false;
      return;
    }
    wasMobileOpen.current = true;
    // While the drawer is open the top bar and the page behind the backdrop are inert, so Tab,
    // screen readers and clicks stay in the drawer. The backdrop stays clickable to close it.
    const sider = siderRef.current;
    const behind = Array.from(sider?.parentElement?.children ?? []).filter(
      (element) => element !== sider && !element.classList.contains("app-sidebar-backdrop")
    );
    behind.forEach((element) => element.setAttribute("inert", ""));
    closeButtonRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      // Leave Escape to an open dropdown or select, or to whatever already handled it.
      const target = event.target instanceof Element ? event.target : null;
      if (event.key !== "Escape" || event.defaultPrevented) return;
      if (target?.closest(".ant-dropdown, .ant-select-dropdown")) return;
      setMobileOpen(false);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      behind.forEach((element) => element.removeAttribute("inert"));
    };
  }, [mobileOpen]);

  const closeMobile = () => setMobileOpen(false);

  useEffect(() => {
    if (organizationId && !sessionStorage.getItem(ORGANIZATION_NAME)) {
      ensureOrganizationName(organizationId, organizationName, setOrganizationName, () => {});
    }
  }, [organizationId, organizationName, setOrganizationName]);

  useEffect(() => {
    if (orgIdFromUrl && !sessionStorage.getItem(ORGANIZATION_NAME)) {
      const foundOrg = organizations.find((org) => org.id === orgIdFromUrl);
      if (foundOrg) {
        sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgIdFromUrl);
        sessionStorage.setItem(ORGANIZATION_NAME, foundOrg.name);
        setOrganizationName(foundOrg.name);
      }
    } else {
      setOrganizationName(sessionStorage.getItem(ORGANIZATION_NAME) || "");
    }

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
    } else if (location.pathname.includes("dependency-graph")) {
      setDefaultSelected(["dependency-graph"]);
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
    organizations,
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
      label: "Organization settings",
      items: [
        { key: "general", label: "General", path: "general", icon: <SettingOutlined /> },
        { key: "teams", label: "Teams", path: "teams", icon: <TeamOutlined /> },
        { key: "tags", label: "Tags", path: "tags", icon: <TagsOutlined /> },
        { key: "variables", label: "Global variables", path: "variables", icon: <CodeOutlined /> },
        { key: "collection", label: "Variable collections", path: "collection", icon: <FolderOutlined /> },
      ],
    },
    {
      key: "version-control",
      label: "Version control",
      items: [
        { key: "vcs", label: "VCS providers", path: "vcs", icon: <BranchesOutlined /> },
        { key: "ssh", label: "SSH keys", path: "ssh", icon: <KeyOutlined /> },
      ],
    },
    {
      key: "security",
      label: "Security",
      items: [
        { key: "agents", label: "Agents", path: "agents", icon: <RobotOutlined /> },
        {
          key: "federated-credentials",
          label: "Federated credentials",
          path: "federated-credentials",
          icon: <SafetyCertificateOutlined />,
        },
        {
          key: "policies",
          label: "Policy sets",
          path: "policies",
          icon: <SecurityScanOutlined />,
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
              Actions <Tag className="app-sidebar-beta">beta</Tag>
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
    { key: "policies", label: "Policies", path: "policies", icon: <SafetyCertificateOutlined /> },
    { key: "locking", label: "Locking", path: "locking", icon: <LockOutlined /> },
    { key: "sshkey", label: "SSH key", path: "sshkey", icon: <KeyOutlined /> },
    { key: "webhook", label: "Webhook", path: "webhook", icon: <ApiOutlined /> },
    { key: "notifications", label: "Notifications", path: "notifications", icon: <BellOutlined /> },
    { key: "state-shared", label: "State shared", path: "state-shared", icon: <ShareAltOutlined /> },
    { key: "team-access", label: "Team access", path: "team-access", icon: <TeamOutlined /> },
    { key: "advanced", label: "Destruction and deletion", path: "advanced", icon: <DeleteOutlined /> },
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
          label: "Account settings",
          children: [
            { key: "tokens", name: "Tokens", icon: <KeyOutlined /> },
            { key: "theme", name: "Theme", icon: <BgColorsOutlined /> },
          ].map((item) => ({
            key: item.key,
            icon: item.icon,
            label: (
              <Link
                to={`/settings/${item.key}`}
                onClick={() => setDefaultSelected([item.key])}
                aria-current={defaultSelected.includes(item.key) ? "page" : undefined}
              >
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
                Back to workspace
              </Link>
            ),
            key: "__back__",
            icon: <LeftOutlined />,
          },
          {
            type: "group" as const,
            key: "workspace-settings",
            label: "Workspace settings",
            children: workspaceSettingsItems.map((item) => ({
              key: item.key,
              icon: item.icon,
              label: (
                <Link
                  to={`${workspaceBasePath}/settings/${item.path}`}
                  onClick={() => handleOrgMenuClick(item.key)}
                  aria-current={defaultSelected.includes(item.key) ? "page" : undefined}
                >
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
                  <Link
                    to={`${orgBasePath}/settings/${item.path}`}
                    onClick={() => handleOrgMenuClick(item.key)}
                    aria-current={defaultSelected.includes(item.key) ? "page" : undefined}
                  >
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
                key: "run-triggers",
                label: (
                  <Link to={`${workspaceBasePath}/run-triggers`} onClick={() => handleOrgMenuClick("run-triggers")}>
                    Run triggers
                  </Link>
                ),
                icon: <NodeIndexOutlined />,
              },
              {
                key: "cascades",
                label: (
                  <Link to={`${workspaceBasePath}/cascades`} onClick={() => handleOrgMenuClick("cascades")}>
                    Cascades
                  </Link>
                ),
                icon: <ApartmentOutlined />,
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
                { key: "dependency-graph", name: "Dependency Graph", icon: <ClusterOutlined /> },
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
    <>
      {isMobile && (
        <header className="app-mobile-bar">
          <button
            ref={openButtonRef}
            type="button"
            className="app-sidebar-collapse-trigger"
            aria-label="Open navigation"
            aria-expanded={mobileOpen}
            aria-controls="app-sidebar"
            onClick={() => setMobileOpen(true)}
          >
            <MenuOutlined />
          </button>
          <Link to="/" className="app-sidebar-home-link">
            <img src={logo} alt="Terrakube" className="app-sidebar-logo" />
          </Link>
        </header>
      )}
      {isMobile && mobileOpen && <div className="app-sidebar-backdrop" aria-hidden="true" onClick={closeMobile} />}
      <Sider
        ref={siderRef}
        id="app-sidebar"
        theme="dark"
        width={240}
        collapsedWidth={64}
        collapsed={effectiveCollapsed}
        trigger={null}
        breakpoint="md"
        onBreakpoint={setIsMobile}
        className={`app-sidebar${mobileOpen ? " app-sidebar--open" : ""}`}
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
              {isMobile && (
                <button
                  ref={closeButtonRef}
                  type="button"
                  className="app-sidebar-collapse-trigger"
                  aria-label="Close navigation"
                  onClick={closeMobile}
                >
                  <DoubleLeftOutlined />
                </button>
              )}
            </div>
          </div>
          {canCollapse && (
            // A tab on the sidebar's edge: always in the same place, collapsed or not.
            <button
              type="button"
              className="app-sidebar-edge-toggle"
              aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
              aria-expanded={!collapsed}
              aria-controls="app-sidebar"
              onClick={handleToggleCollapsed}
            >
              {collapsed ? <DoubleRightOutlined /> : <DoubleLeftOutlined />}
            </button>
          )}
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
            onClick={() => setMobileOpen(false)}
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
    </>
  );
}
