import { AppstoreOutlined, CloudOutlined, SettingOutlined } from "@ant-design/icons";
import { Menu } from "antd";
import "antd/dist/reset.css";
import { useEffect, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import organizationService from "@/modules/organizations/organizationService";
import { FlatOrganization } from "../types";
import "./Home.css";
import { ThemeMode } from "../../config/themeConfig";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
  themeMode?: ThemeMode;
};

// Helper function to ensure organization name is properly set
const ensureOrganizationName = (
  orgId: string,
  currentOrgName: string,
  setOrgName: (name: string) => void,
  onComplete: () => void
) => {
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
};

export const MainMenu = ({ organizationName, setOrganizationName, themeMode }: Props) => {
  const [orgs, setOrgs] = useState<FlatOrganization[]>([]);
  const [defaultSelected, setDefaultSelected] = useState(["registry"]);
  const location = useLocation();
  const navigate = useNavigate();
  const params = location.pathname.split("/");
  const orgIdFromUrl = params.length > 2 && params[1] === "organizations" ? params[2] : null;
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE) || orgIdFromUrl;

  // Load organization name directly when component mounts or organizationId changes
  useEffect(() => {
    if (organizationId && (!sessionStorage.getItem(ORGANIZATION_NAME) || organizationName === "select organization")) {
      ensureOrganizationName(
        organizationId,
        organizationName,
        setOrganizationName,
        () => {} // No additional action needed
      );
    }
  }, [organizationId, organizationName, setOrganizationName]);

  useEffect(() => {
    // Load all organizations via GraphQL (faster than REST, avoids loading all relationships)
    organizationService
      .listOrganizationsGraphQL()
      .then((organizations) => {
        setOrgs(organizations);

        // Check if we have an org ID in the URL but not in session storage
        if (
          orgIdFromUrl &&
          (!sessionStorage.getItem(ORGANIZATION_NAME) || organizationName === "select organization")
        ) {
          // Find the organization name by ID
          const foundOrg = organizations.find((org) => org.id === orgIdFromUrl);
          if (foundOrg) {
            sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgIdFromUrl);
            sessionStorage.setItem(ORGANIZATION_NAME, foundOrg.name);
            setOrganizationName(foundOrg.name);
          } else {
            // If not found in the list, fetch directly
            ensureOrganizationName(
              orgIdFromUrl,
              "",
              setOrganizationName,
              () => {} // No additional action needed
            );
          }
        } else {
          setOrganizationName(sessionStorage.getItem(ORGANIZATION_NAME) || "select organization");
        }
      })
      .catch((error) => {
        console.error("Failed to load organizations:", error);
      });

    if (location.pathname.includes("registry")) {
      setDefaultSelected(["registry"]);
    } else if (location.pathname.includes("settings")) {
      setDefaultSelected(["settings"]);
    } else {
      setDefaultSelected(["workspaces"]);
    }
  }, [orgIdFromUrl, location.pathname, setOrganizationName]);

  const handleClick = (e: { key: string }) => {
    if (e.key === "new") navigate("/organizations/create");
    else {
      // Use the helper function for organization change (with full page reload)
      ensureOrganizationName(e.key, "", setOrganizationName, () => {
        // Navigate after setting organization name with full page reload
        window.location.href = `/organizations/${e.key}/workspaces`;
      });
    }
  };

  const handleSectionNavigation = (section: string) => {
    // Use the helper function for section navigation
    ensureOrganizationName(orgIdFromUrl!, organizationName, setOrganizationName, () => {
      // Navigate within the same organization
      navigate(`/organizations/${orgIdFromUrl}/${section}`);
      setDefaultSelected([section]);
    });
  };

  const items = [
    ...(orgIdFromUrl
      ? [
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
      : []),
  ];

  return (
    <>
      <Menu selectedKeys={defaultSelected} theme="dark" mode="horizontal" items={items} />
    </>
  );
};

export default MainMenu;
