import { Layout, ConfigProvider } from "antd";
import { lazy, Suspense, useState, useEffect, type Dispatch, type SetStateAction } from "react";
import {
  RouterProvider,
  createBrowserRouter,
  Outlet,
  useParams,
  useNavigate,
  useOutletContext,
  useLocation,
} from "react-router-dom";
import { useAuth } from "../../config/authConfig";
import { getBasePath } from "../../config/basePath";
import { getThemeConfig } from "../../config/themeConfig";
import { ThemeProvider, useTheme } from "../../context/ThemeContext";
import Login from "../Login/Login";
import "./App.css";
import "./Home.css";
import AppSidebar from "@/modules/layout/AppSidebar/AppSidebar";
import LoadingFallback from "@/components/LoadingFallback";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import organizationService from "@/modules/organizations/organizationService";
import { FlatOrganization } from "../types";
const { Footer } = Layout;

type AppRouteContext = {
  organizationName: string;
  setOrganizationName: Dispatch<SetStateAction<string>>;
  setWorkspaceManageState: Dispatch<SetStateAction<boolean>>;
};

// Organizations
const CreateOrganization = lazy(() =>
  import("../Organizations/Create").then((module) => ({ default: module.CreateOrganization }))
);
const OrganizationsPickerPage = lazy(() => import("@/modules/organizations/OrganizationsPickerPage"));
const OrganizationsDetailPage = lazy(() => import("@/modules/organizations/OrganizationDetailsPage"));
const ProjectsPage = lazy(() => import("@/modules/projects/ProjectsPage"));
const ProjectDetailPage = lazy(() => import("@/modules/projects/ProjectDetailPage"));

// Workspaces
const CreateWorkspace = lazy(() =>
  import("../Workspaces/Create").then((module) => ({ default: module.CreateWorkspace }))
);
const ImportWorkspace = lazy(() =>
  import("../Workspaces/Import").then((module) => ({ default: module.ImportWorkspace }))
);
const WorkspaceDetails = lazy(() =>
  import("../Workspaces/Details").then((module) => ({ default: module.WorkspaceDetails }))
);

// Modules and registry
const CreateModule = lazy(() => import("../Modules/Create").then((module) => ({ default: module.CreateModule })));
const Registry = lazy(() => import("../Modules/Registry").then((module) => ({ default: module.Registry })));
const PublicRegistrySearch = lazy(() =>
  import("../Modules/PublicRegistrySearch").then((module) => ({ default: module.PublicRegistrySearch }))
);
const ProviderDetails = lazy(() =>
  import("../Providers/ProviderDetails").then((module) => ({ default: module.ProviderDetails }))
);
const ModuleDetails = lazy(() => import("../Modules/Details").then((module) => ({ default: module.ModuleDetails })));

// Settings
const OrganizationSettings = lazy(() =>
  import("../Settings/Settings").then((module) => ({ default: module.OrganizationSettings }))
);
const UserSettingsPage = lazy(() =>
  import("@/modules/user/UserSettingsPage").then((module) => ({ default: module.UserSettingsPage }))
);

// API Docs
const ApiDocsPage = lazy(() =>
  import("@/modules/apiDocs/ApiDocsPage").then((module) => ({ default: module.ApiDocsPage }))
);

// Helper component to extract URL parameters for collection routes
const CollectionSettingsWrapper = ({ mode }: { mode: "edit" | "detail" }) => {
  const { collectionid } = useParams();
  return <OrganizationSettings selectedTab="9" collectionMode={mode} collectionId={collectionid} />;
};

const useAppRouteContext = () => useOutletContext<AppRouteContext>();

const CreateOrganizationRoute = () => {
  const { setOrganizationName } = useAppRouteContext();
  return <CreateOrganization setOrganizationName={setOrganizationName} />;
};

const OrganizationsDetailRoute = () => {
  const { organizationName, setOrganizationName } = useAppRouteContext();
  return <OrganizationsDetailPage setOrganizationName={setOrganizationName} organizationName={organizationName} />;
};

const OrganizationsProjectsRoute = () => {
  const { organizationName, setOrganizationName } = useAppRouteContext();
  return <ProjectsPage setOrganizationName={setOrganizationName} organizationName={organizationName} />;
};

const OrganizationsProjectDetailRoute = () => {
  const { organizationName, setOrganizationName } = useAppRouteContext();
  return <ProjectDetailPage setOrganizationName={setOrganizationName} organizationName={organizationName} />;
};

const WorkspaceDetailsRoute = ({
  selectedTab,
  settingsSection,
}: {
  selectedTab?: string;
  settingsSection?: string;
}) => {
  const { setOrganizationName, setWorkspaceManageState } = useAppRouteContext();
  return (
    <WorkspaceDetails
      setOrganizationName={setOrganizationName}
      setWorkspaceManageState={setWorkspaceManageState}
      selectedTab={selectedTab}
      settingsSection={settingsSection}
    />
  );
};

const RegistryRoute = () => {
  const { organizationName, setOrganizationName } = useAppRouteContext();
  return <Registry setOrganizationName={setOrganizationName} organizationName={organizationName} />;
};

const PublicRegistrySearchRoute = () => {
  const { organizationName } = useAppRouteContext();
  return <PublicRegistrySearch organizationName={organizationName} />;
};

const ProviderDetailsRoute = () => {
  const { organizationName } = useAppRouteContext();
  return <ProviderDetails organizationName={organizationName} />;
};

const ModuleDetailsRoute = () => {
  const { organizationName } = useAppRouteContext();
  return <ModuleDetails organizationName={organizationName} />;
};

const AppLayout = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const [organizationName, setOrganizationName] = useState<string>("");
  const [orgs, setOrgs] = useState<FlatOrganization[]>([]);
  const [workspaceManageState, setWorkspaceManageState] = useState(false);
  const { colorScheme, themeMode } = useTheme();

  useEffect(() => {
    const pathname = window.location.pathname;
    const paths = pathname.split("/");
    const orgIdIndex = paths.indexOf("organizations") + 1;

    if (orgIdIndex > 0 && orgIdIndex < paths.length) {
      const orgId = paths[orgIdIndex];
      if (orgId) {
        const storedOrgName = sessionStorage.getItem(ORGANIZATION_NAME);
        const storedOrgId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);

        if (storedOrgName && storedOrgId === orgId) {
          setOrganizationName(storedOrgName);
        } else {
          organizationService
            .getOrganizationNameGraphQL(orgId)
            .then((orgName) => {
              if (orgName) {
                sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgId);
                sessionStorage.setItem(ORGANIZATION_NAME, orgName);
                setOrganizationName(orgName);
              }
            })
            .catch((err) => {
              console.error("Failed to load organization:", err);
            });
        }
      }
    } else {
      const storedOrgName = sessionStorage.getItem(ORGANIZATION_NAME);
      if (storedOrgName) {
        setOrganizationName(storedOrgName);
      }
    }
  }, []);

  useEffect(() => {
    // Re-fetch on every navigation so newly created/deleted organizations
    // show up in the header dropdown without a full page reload.
    organizationService
      .listOrganizationsGraphQL()
      .then((organizations) => {
        setOrgs(organizations);
      })
      .catch((error) => {
        console.error("Failed to load organizations:", error);
      });
  }, [location.pathname]);

  const handleOrgChange = (orgId: string) => {
    const org = orgs.find((o) => o.id === orgId);
    if (org) {
      sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgId);
      sessionStorage.setItem(ORGANIZATION_NAME, org.name);
      setOrganizationName(org.name);
    }

    // Stay on the same top-level section (workspaces/registry/settings/projects)
    // under the new organization instead of always bouncing to Workspaces.
    // Deeper sub-paths (a specific workspace, run, etc.) are dropped since
    // those resource ids belong to the old organization and won't resolve
    // under the new one.
    const knownSections = ["workspaces", "registry", "settings", "projects"];
    const paths = location.pathname.split("/").filter(Boolean);
    const orgIdx = paths.indexOf("organizations");
    const section = orgIdx >= 0 ? paths[orgIdx + 2] : undefined;

    navigate(`/organizations/${orgId}/${section && knownSections.includes(section) ? section : "workspaces"}`);
  };

  return (
    <ConfigProvider theme={getThemeConfig(colorScheme, themeMode)}>
      <Layout className="layout mh-100">
        <AppSidebar
          organizationName={organizationName}
          setOrganizationName={setOrganizationName}
          organizations={orgs}
          onOrgChange={handleOrgChange}
          onManageOrgs={() => navigate("/organizations")}
          workspaceManageState={workspaceManageState}
        />
        <Layout className="app-content-shell">
          <div className="app-content-scroll">
            <Outlet context={{ organizationName, setOrganizationName, setWorkspaceManageState }} />
            <Footer style={{ textAlign: "center" }}>
              Terrakube {window._env_.REACT_APP_TERRAKUBE_VERSION} ©{new Date().getFullYear()}
            </Footer>
          </div>
        </Layout>
      </Layout>
    </ConfigProvider>
  );
};

const App = () => {
  const auth = useAuth();
  const expiry = auth?.user?.expires_at;
  const basePath = getBasePath();

  // Checking with the expiry time in the localstorage and when it has crossed the access has been revoked so It will clear the local storage and by default with no localstorage object it will route to login page.
  if (auth.isAuthenticated && auth?.user && expiry !== undefined && Math.floor(Date.now() / 1000) > expiry) {
    localStorage.clear();
  }

  if (auth.isLoading) {
    return null;
  }

  if (!auth.isAuthenticated) {
    return <Login />;
  }

  const router = createBrowserRouter(
    [
      {
        path: "/",
        element: <AppLayout />,
        children: [
          {
            path: "/",
            element: <OrganizationsPickerPage />,
          },
          {
            path: "/organizations",
            element: <OrganizationsPickerPage />,
          },
          {
            path: "/organizations/create",
            element: <CreateOrganizationRoute />,
          },
          {
            path: "/organizations/:id/workspaces",
            element: <OrganizationsDetailRoute />,
          },
          {
            path: "/organizations/:id/projects",
            element: <OrganizationsProjectsRoute />,
          },
          {
            path: "/organizations/:orgid/projects/:id",
            element: <OrganizationsProjectDetailRoute />,
          },
          {
            path: "/workspaces/create",
            element: <CreateWorkspace />,
          },
          {
            path: "/workspaces/import",
            element: <ImportWorkspace />,
          },
          {
            path: "/workspaces/:id",
            element: <WorkspaceDetailsRoute />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id",
            element: <WorkspaceDetailsRoute />,
          },
          {
            path: "/workspaces/:id/runs",
            element: <WorkspaceDetailsRoute selectedTab="2" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/runs",
            element: <WorkspaceDetailsRoute selectedTab="2" />,
          },
          {
            path: "/workspaces/:id/runs/:runid",
            element: <WorkspaceDetailsRoute selectedTab="2" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/runs/:runid",
            element: <WorkspaceDetailsRoute selectedTab="2" />,
          },
          {
            path: "/workspaces/:id/states",
            element: <WorkspaceDetailsRoute selectedTab="3" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/states",
            element: <WorkspaceDetailsRoute selectedTab="3" />,
          },
          {
            path: "/workspaces/:id/variables",
            element: <WorkspaceDetailsRoute selectedTab="4" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/variables",
            element: <WorkspaceDetailsRoute selectedTab="4" />,
          },
          {
            path: "/workspaces/:id/schedules",
            element: <WorkspaceDetailsRoute selectedTab="5" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/schedules",
            element: <WorkspaceDetailsRoute selectedTab="5" />,
          },
          {
            path: "/workspaces/:id/settings",
            element: <WorkspaceDetailsRoute selectedTab="6" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings",
            element: <WorkspaceDetailsRoute selectedTab="6" />,
          },
          {
            path: "/workspaces/:id/settings/general",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="general" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/general",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="general" />,
          },
          {
            path: "/workspaces/:id/settings/locking",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="locking" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/locking",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="locking" />,
          },
          {
            path: "/workspaces/:id/settings/sshkey",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="sshkey" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/sshkey",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="sshkey" />,
          },
          {
            path: "/workspaces/:id/settings/webhook",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="webhook" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/webhook",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="webhook" />,
          },
          {
            path: "/workspaces/:id/settings/notifications",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="notifications" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/notifications",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="notifications" />,
          },
          {
            path: "/workspaces/:id/settings/state-shared",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="state-shared" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/state-shared",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="state-shared" />,
          },
          {
            path: "/workspaces/:id/settings/team-access",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="team-access" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/team-access",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="team-access" />,
          },
          {
            path: "/workspaces/:id/settings/advanced",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="advanced" />,
          },
          {
            path: "/organizations/:orgid/workspaces/:id/settings/advanced",
            element: <WorkspaceDetailsRoute selectedTab="6" settingsSection="advanced" />,
          },
          {
            path: "/organizations/:orgid/registry",
            element: <RegistryRoute />,
          },
          {
            path: "/organizations/:orgid/registry/search",
            element: <PublicRegistrySearchRoute />,
          },
          {
            path: "/organizations/:orgid/registry/create",
            element: <CreateModule />,
          },
          {
            path: "/organizations/:orgid/registry/providers/:providerid",
            element: <ProviderDetailsRoute />,
          },
          {
            path: "/organizations/:orgid/registry/:id",
            element: <ModuleDetailsRoute />,
          },
          {
            path: "/organizations/:orgid/settings",
            element: <OrganizationSettings />,
          },
          {
            path: "/organizations/:orgid/settings/general",
            element: <OrganizationSettings selectedTab="1" />,
          },
          {
            path: "/organizations/:orgid/settings/teams",
            element: <OrganizationSettings selectedTab="2" />,
          },
          {
            path: "/organizations/:orgid/settings/variables",
            element: <OrganizationSettings selectedTab="3" />,
          },
          {
            path: "/organizations/:orgid/settings/vcs",
            element: <OrganizationSettings selectedTab="4" />,
          },
          {
            path: "/organizations/:orgid/settings/vcs/new/:vcsName",
            element: <OrganizationSettings selectedTab="4" vcsMode="new" />,
          },
          {
            path: "/settings/tokens",
            element: <UserSettingsPage />,
          },
          {
            path: "/settings/theme",
            element: <UserSettingsPage />,
          },
          {
            path: "/organizations/:orgid/settings/ssh",
            element: <OrganizationSettings selectedTab="6" />,
          },
          {
            path: "/organizations/:orgid/settings/tags",
            element: <OrganizationSettings selectedTab="7" />,
          },
          {
            path: "/organizations/:orgid/settings/agents",
            element: <OrganizationSettings selectedTab="8" />,
          },
          {
            path: "/organizations/:orgid/settings/federated-credentials",
            element: <OrganizationSettings selectedTab="11" />,
          },
          {
            path: "/organizations/:orgid/settings/templates",
            element: <OrganizationSettings selectedTab="5" />,
          },
          {
            path: "/organizations/:orgid/settings/actions",
            element: <OrganizationSettings selectedTab="10" />,
          },
          {
            path: "/organizations/:orgid/settings/notifications",
            element: <OrganizationSettings selectedTab="12" />,
          },
          {
            path: "/organizations/:orgid/settings/collection",
            element: <OrganizationSettings selectedTab="9" />,
          },
          {
            path: "/organizations/:orgid/settings/collection/new",
            element: <OrganizationSettings selectedTab="9" collectionMode="new" />,
          },
          {
            path: "/organizations/:orgid/settings/collection/edit/:collectionid",
            element: <CollectionSettingsWrapper mode="edit" />,
          },
          {
            path: "/organizations/:orgid/settings/collection/:collectionid",
            element: <CollectionSettingsWrapper mode="detail" />,
          },
        ],
      },
      {
        // Full-bleed: Scalar renders its own sidebar/nav, so this route skips
        // AppLayout entirely rather than duplicating it alongside ours.
        path: "/api-docs",
        element: <ApiDocsPage />,
      },
    ],
    {
      basename: basePath,
    }
  );

  return (
    <ThemeProvider>
      <Suspense fallback={<LoadingFallback />}>
        <RouterProvider router={router} />
      </Suspense>
    </ThemeProvider>
  );
};

export default App;
