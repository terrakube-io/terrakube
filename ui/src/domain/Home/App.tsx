import { Layout, ConfigProvider } from "antd";
import { lazy, Suspense, useState, useEffect, type Dispatch, type SetStateAction } from "react";
import {
  RouterProvider,
  createBrowserRouter,
  Outlet,
  useParams,
  useNavigate,
  useOutletContext,
} from "react-router-dom";
import { useAuth } from "../../config/authConfig";
import { getThemeConfig } from "../../config/themeConfig";
import { ThemeProvider, useTheme } from "../../context/ThemeContext";
import Login from "../Login/Login";
import "./App.css";
import MainMenu from "./MainMenu";
import { HelpMenu } from "@/components/HelpMenu";
import LoadingFallback from "@/components/LoadingFallback";
import { UserMenu } from "@/components/UserMenu";
import { OrganizationSelector } from "@/components/OrganizationSelector";
import logo from "./white_logo.png";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import organizationService from "@/modules/organizations/organizationService";
import { FlatOrganization } from "../types";
const { Header, Footer } = Layout;

type AppRouteContext = {
  organizationName: string;
  setOrganizationName: Dispatch<SetStateAction<string>>;
};

// Organizations
const CreateOrganization = lazy(() =>
  import("../Organizations/Create").then((module) => ({ default: module.CreateOrganization }))
);
const OrganizationsPickerPage = lazy(() => import("@/modules/organizations/OrganizationsPickerPage"));
const OrganizationsDetailPage = lazy(() => import("@/modules/organizations/OrganizationDetailsPage"));

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

const WorkspaceDetailsRoute = ({ selectedTab }: { selectedTab?: string }) => {
  const { setOrganizationName } = useAppRouteContext();
  return <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab={selectedTab} />;
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
  const [organizationName, setOrganizationName] = useState<string>("");
  const [orgs, setOrgs] = useState<FlatOrganization[]>([]);
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
    organizationService
      .listOrganizationsGraphQL()
      .then((organizations) => {
        setOrgs(organizations);
      })
      .catch((error) => {
        console.error("Failed to load organizations:", error);
      });
  }, []);

  const handleOrgChange = (orgId: string) => {
    const org = orgs.find((o) => o.id === orgId);
    if (org) {
      sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgId);
      sessionStorage.setItem(ORGANIZATION_NAME, org.name);
      setOrganizationName(org.name);
    }
    navigate(`/organizations/${orgId}/workspaces`);
  };

  return (
    <ConfigProvider key={`${colorScheme}-${themeMode}`} theme={getThemeConfig(colorScheme, themeMode)}>
      <Layout className="layout mh-100" key={organizationName || "no-org"}>
        <Header>
          <a>
            <img className="logo" src={logo} alt="Logo"></img>
          </a>
          <OrganizationSelector
            organizationName={organizationName}
            organizations={orgs}
            onOrgChange={handleOrgChange}
            onManageOrgs={() => navigate("/organizations")}
          />
          <div className="menu">
            <MainMenu
              organizationName={organizationName}
              setOrganizationName={setOrganizationName}
              themeMode={themeMode}
            />
          </div>
          <div className="user">
            <HelpMenu />
            <UserMenu />
          </div>
        </Header>
        <Outlet context={{ organizationName, setOrganizationName }} />
        <Footer style={{ textAlign: "center" }}>
          Terrakube {window._env_.REACT_APP_TERRAKUBE_VERSION} ©{new Date().getFullYear()}
        </Footer>
      </Layout>
    </ConfigProvider>
  );
};

const App = () => {
  const auth = useAuth();
  const expiry = auth?.user?.expires_at;

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

  const router = createBrowserRouter([
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
          path: "/organizations/:orgid/settings/actions",
          element: <OrganizationSettings selectedTab="10" />,
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
  ]);

  return (
    <ThemeProvider>
      <Suspense fallback={<LoadingFallback />}>
        <RouterProvider router={router} />
      </Suspense>
    </ThemeProvider>
  );
};

export default App;
