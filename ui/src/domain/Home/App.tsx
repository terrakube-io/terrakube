import { Layout, ConfigProvider } from "antd";
import { useState, useEffect } from "react";
import { RouterProvider, createBrowserRouter, Outlet, useParams } from "react-router-dom";
import { useAuth } from "../../config/authConfig";
import {
  getThemeConfig,
  ColorSchemeOption,
  ThemeMode,
  defaultColorScheme,
  defaultThemeMode,
} from "../../config/themeConfig";
import Login from "../Login/Login";
import { CreateModule } from "../Modules/Create";
import { ModuleDetails } from "../Modules/Details";
import { PublicRegistrySearch } from "../Modules/PublicRegistrySearch";
import { ProviderDetails } from "../Providers/ProviderDetails";
import { Registry } from "../Modules/Registry";
import { CreateOrganization } from "../Organizations/Create";
import { OrganizationSettings } from "../Settings/Settings";
import { CreateWorkspace } from "../Workspaces/Create";
import { WorkspaceDetails } from "../Workspaces/Details";
import { ImportWorkspace } from "../Workspaces/Import";
import "./App.css";
import MainMenu from "./MainMenu";
import { ProfilePicture } from "./ProfilePicture";
import logo from "./white_logo.png";
import { UserSettingsPage } from "@/modules/user/UserSettingsPage";
import OrganizationsPickerPage from "@/modules/organizations/OrganizationsPickerPage";
import OrganizationsDetailPage from "@/modules/organizations/OrganizationDetailsPage";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
const { Header, Footer } = Layout;

// Helper component to extract URL parameters for collection routes
const CollectionSettingsWrapper = ({ mode }: { mode: "edit" | "detail" }) => {
  const { collectionid } = useParams();
  return <OrganizationSettings selectedTab="9" collectionMode={mode} collectionId={collectionid} />;
};

const App = () => {
  const auth = useAuth();
  const [organizationName, setOrganizationName] = useState<string>("");
  const [colorScheme, setColorScheme] = useState<ColorSchemeOption>(defaultColorScheme);
  const [themeMode, setThemeMode] = useState<ThemeMode>(defaultThemeMode);
  const expiry = auth?.user?.expires_at;

  useEffect(() => {
    // Load color scheme and theme mode preferences from localStorage
    const savedScheme = localStorage.getItem("terrakube-color-scheme") as ColorSchemeOption;
    const savedThemeMode = localStorage.getItem("terrakube-theme-mode") as ThemeMode;
    if (savedScheme) {
      setColorScheme(savedScheme);
    }
    if (savedThemeMode) {
      setThemeMode(savedThemeMode);
    }

    // Initialize organization name from URL or session storage
    const pathname = window.location.pathname;
    const paths = pathname.split("/");
    const orgIdIndex = paths.indexOf("organizations") + 1;

    if (orgIdIndex > 0 && orgIdIndex < paths.length) {
      const orgId = paths[orgIdIndex];
      if (orgId) {
        // Check if we already have the org name in session storage
        const storedOrgName = sessionStorage.getItem(ORGANIZATION_NAME);
        const storedOrgId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);

        if (storedOrgName && storedOrgId === orgId) {
          setOrganizationName(storedOrgName);
        } else {
          // Fetch the organization name
          axiosInstance
            .get(`organization/${orgId}`)
            .then((response) => {
              if (response.data && response.data.data) {
                const orgName = response.data.data.attributes.name;
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
      // No org ID in URL, use session storage if available
      const storedOrgName = sessionStorage.getItem(ORGANIZATION_NAME);
      if (storedOrgName) {
        setOrganizationName(storedOrgName);
      }
    }
  }, []);

  // Checking with the expiry time in the localstorage and when it has crossed the access has been revoked so It will clear the local storage and by default with no localstorage object it will route to login page.
  if (auth.isAuthenticated && auth?.user && expiry !== undefined && Math.floor(Date.now() / 1000) > expiry) {
    localStorage.clear();
  }

  if (!auth.isAuthenticated) {
    return <Login />;
  }

  const router = createBrowserRouter([
    {
      path: "/",
      element: (
        <ConfigProvider theme={getThemeConfig(colorScheme, themeMode)}>
          <Layout className="layout mh-100">
            <Header>
              <a>
                <img className="logo" src={logo} alt="Logo"></img>
              </a>
              <div className="menu">
                <MainMenu
                  organizationName={organizationName}
                  setOrganizationName={setOrganizationName}
                  themeMode={themeMode}
                />
              </div>
              <div className="user">
                <ProfilePicture />
              </div>
            </Header>
            <Outlet />
            <Footer style={{ textAlign: "center" }}>
              Terrakube {window._env_.REACT_APP_TERRAKUBE_VERSION} ©{new Date().getFullYear()}
            </Footer>
          </Layout>
        </ConfigProvider>
      ),
      children: [
        {
          path: "/",
          element: <OrganizationsPickerPage />,
        },
        {
          path: "/organizations/create",
          element: <CreateOrganization setOrganizationName={setOrganizationName} />,
        },
        {
          path: "/organizations/:id/workspaces",
          element: (
            <OrganizationsDetailPage setOrganizationName={setOrganizationName} organizationName={organizationName} />
          ),
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
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} />,
        },
        {
          path: "/organizations/:orgid/workspaces/:id",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} />,
        },
        {
          path: "/workspaces/:id/runs",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="2" />,
        },
        {
          path: "/organizations/:orgid/workspaces/:id/runs",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="2" />,
        },
        {
          path: "/workspaces/:id/runs/:runid",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="2" />,
        },
        {
          path: "/organizations/:orgid/workspaces/:id/runs/:runid",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="2" />,
        },
        {
          path: "/workspaces/:id/states",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="3" />,
        },
        {
          path: "/organizations/:orgid/workspaces/:id/states",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="3" />,
        },
        {
          path: "/workspaces/:id/variables",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="4" />,
        },
        {
          path: "/organizations/:orgid/workspaces/:id/variables",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="4" />,
        },
        {
          path: "/workspaces/:id/schedules",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="5" />,
        },
        {
          path: "/organizations/:orgid/workspaces/:id/schedules",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="5" />,
        },
        {
          path: "/workspaces/:id/settings",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="6" />,
        },
        {
          path: "/organizations/:orgid/workspaces/:id/settings",
          element: <WorkspaceDetails setOrganizationName={setOrganizationName} selectedTab="6" />,
        },
        {
          path: "/organizations/:orgid/registry",
          element: <Registry setOrganizationName={setOrganizationName} organizationName={organizationName} />,
        },
        {
          path: "/organizations/:orgid/registry/search",
          element: <PublicRegistrySearch organizationName={organizationName} />,
        },
        {
          path: "/organizations/:orgid/registry/create",
          element: <CreateModule />,
        },
        {
          path: "/organizations/:orgid/registry/providers/:providerid",
          element: <ProviderDetails organizationName={organizationName} />,
        },
        {
          path: "/organizations/:orgid/registry/:id",
          element: <ModuleDetails organizationName={organizationName} />,
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

  return <RouterProvider router={router} />;
};

export default App;
