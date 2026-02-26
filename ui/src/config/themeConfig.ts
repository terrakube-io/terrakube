import { ThemeConfig, theme } from "antd";

export type ColorSchemeOption = "default" | "terrakube";
export type ThemeMode = "light" | "dark";

const darkThemeTokens = {
  // Backgrounds - rich dark, not pure black
  colorBgBase: "#0d1117",
  colorBgContainer: "#161b22",
  colorBgElevated: "#1c2128",
  colorBgLayout: "#0d1117",
  colorBgSpotlight: "#21262d",

  // Borders - subtle but visible
  colorBorder: "#30363d",
  colorBorderSecondary: "#21262d",

  // Text - high contrast
  colorText: "#e6edf3",
  colorTextSecondary: "#8b949e",
  colorTextTertiary: "#6e7681",
  colorTextQuaternary: "#484f58",

  // Fill colors
  colorFill: "#21262d",
  colorFillSecondary: "#30363d",
  colorFillTertiary: "#161b22",
  colorFillQuaternary: "#0d1117",

  // Split/divider
  colorSplit: "#21262d",
};

export const getThemeConfig = (colorScheme: ColorSchemeOption, themeMode: ThemeMode): ThemeConfig => {
  const colorPrimary = colorScheme === "default" ? "#1890ff" : "#722ED1";

  if (typeof document !== "undefined" && document.documentElement) {
    document.documentElement.setAttribute("data-theme", themeMode);
    document.documentElement.setAttribute("data-color-scheme", colorScheme);
  }

  if (themeMode === "dark") {
    const isTerrakube = colorScheme === "terrakube";
    return {
      algorithm: theme.darkAlgorithm,
      token: {
        colorPrimary,
        ...darkThemeTokens,
        colorPrimaryBg: isTerrakube ? "#1a0a2e" : "#0d2942",
        colorPrimaryBgHover: isTerrakube ? "#2d1548" : "#113a5d",
      },
      components: {
        Layout: {
          headerBg: "#161b22",
          bodyBg: "#0d1117",
          footerBg: "#0d1117",
        },
        Menu: {
          darkItemBg: "#161b22",
          darkPopupBg: "#161b22",
          darkItemSelectedBg: "#21262d",
          darkItemHoverBg: "#21262d",
          itemBg: "#161b22",
          popupBg: "#161b22",
        },
        Card: {
          colorBgContainer: "#161b22",
          colorBorderSecondary: "#30363d",
        },
        Input: {
          colorBgContainer: "#0d1117",
          colorBorder: "#30363d",
        },
        Select: {
          colorBgContainer: "#0d1117",
          colorBgElevated: "#1c2128",
          optionSelectedBg: "#21262d",
        },
        Button: {
          defaultBg: "#21262d",
          defaultBorderColor: "#30363d",
          defaultColor: "#e6edf3",
        },
        Table: {
          colorBgContainer: "#161b22",
          headerBg: "#1c2128",
          rowHoverBg: "#21262d",
        },
        Modal: {
          contentBg: "#1c2128",
          headerBg: "#1c2128",
        },
        Drawer: {
          colorBgElevated: "#1c2128",
        },
        Dropdown: {
          colorBgElevated: "#1c2128",
        },
        Popover: {
          colorBgElevated: "#1c2128",
        },
        Segmented: {
          itemSelectedBg: "#30363d",
          trackBg: "#161b22",
        },
        Tabs: {
          itemColor: "#8b949e",
          itemSelectedColor: "#e6edf3",
          itemHoverColor: "#e6edf3",
        },
        Tag: {
          defaultBg: "#21262d",
          defaultColor: "#e6edf3",
        },
        Badge: {
          colorBgContainer: "#161b22",
        },
        Divider: {
          colorSplit: "#30363d",
        },
        Breadcrumb: {
          itemColor: "#8b949e",
          linkColor: "#8b949e",
          linkHoverColor: "#e6edf3",
          separatorColor: "#6e7681",
        },
        Typography: {
          colorText: "#e6edf3",
          colorTextSecondary: "#8b949e",
        },
        Form: {
          labelColor: "#e6edf3",
        },
        Alert: {
          colorText: "#e6edf3",
          defaultPadding: "8px 12px",
        },
        Checkbox: {
          colorBgContainer: "#0d1117",
          colorBorder: "#30363d",
        },
        Radio: {
          colorBgContainer: "#0d1117",
          colorBorder: "#30363d",
        },
        Pagination: {
          itemActiveBg: "#21262d",
          itemBg: "#0d1117",
        },
      },
    };
  }

  return {
    algorithm: theme.defaultAlgorithm,
    token: {
      colorPrimary,
    },
    components: {
      Menu: {
        darkItemBg: "#1e2837",
        darkPopupBg: "#1e2837",
        darkSubMenuItemBg: "#1e2837",
      },
      Layout: {
        headerBg: "#1e2837",
      },
    },
  };
};

export const defaultColorScheme: ColorSchemeOption = "default";
export const defaultThemeMode: ThemeMode = "light";

// Export a default theme configuration using the default color scheme and theme mode
export const themeConfig = getThemeConfig(defaultColorScheme, defaultThemeMode);
