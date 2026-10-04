import { ThemeConfig, theme } from "antd";

export type ColorSchemeOption = "blue" | "terrakube";
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
  // Descriptions and secondary text: antd's default falls below 4.5:1.
  colorTextDescription: "#8b949e",
  // Tertiary text (help lines), placeholders and select arrows: the GitHub-dark defaults fall below 4.5:1 / 3:1.
  colorTextTertiary: "#7d8590",
  colorTextQuaternary: "#7d8590",
  colorTextPlaceholder: "#7d8590",

  // Status: antd's dark derivations give 3.3–4.0:1 for error and info text on their tinted tags.
  colorError: "#f85149",
  colorInfo: "#4493f8",

  // Fill colors
  colorFill: "#21262d",
  colorFillSecondary: "#30363d",
  colorFillTertiary: "#161b22",
  colorFillQuaternary: "#0d1117",

  // Split/divider
  colorSplit: "#21262d",
};

export const getThemeConfig = (colorScheme: ColorSchemeOption, themeMode: ThemeMode): ThemeConfig => {
  const colorPrimary = colorScheme === "blue" ? "#1890ff" : "#722ED1";
  const isTerrakube = colorScheme === "terrakube";

  if (typeof document !== "undefined" && document.documentElement) {
    document.documentElement.setAttribute("data-theme", themeMode);
    document.documentElement.setAttribute("data-color-scheme", colorScheme);
  }

  if (themeMode === "dark") {
    return {
      algorithm: theme.darkAlgorithm,
      token: {
        colorPrimary,
        ...darkThemeTokens,
        colorPrimaryBg: isTerrakube ? "#1a0a2e" : "#0d2942",
        colorPrimaryBgHover: isTerrakube ? "#2d1548" : "#113a5d",
        colorLink: isTerrakube ? "#b37feb" : "#58a6ff",
      },
      components: {
        Layout: {
          headerBg: "#161b22",
          bodyBg: "#0d1117",
          footerBg: "#0d1117",
          siderBg: "#161b22",
        },
        Menu: {
          darkItemBg: "#161b22",
          darkPopupBg: "#161b22",
          darkItemSelectedBg: isTerrakube ? "#2d1548" : "#0d2942",
          darkItemHoverBg: isTerrakube ? "#2a1f3d" : "#16232f",
          darkSubMenuItemBg: "#161b22",
          // Danger items ("Delete module"): antd's colorError is below 4.5:1 on the menu surfaces.
          dangerItemColor: "#ff7875",
          dangerItemHoverColor: "#ff7875",
          dangerItemSelectedColor: "#ff7875",
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
          headerSortActiveBg: "#21262d",
          headerSortHoverBg: "#21262d",
          rowHoverBg: "#21262d",
        },
        Avatar: {
          // Default avatars: graphite tile, light glyph (antd uses the placeholder grey with white).
          colorTextPlaceholder: "#30363d",
          colorTextLightSolid: "#e6edf3",
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
          // Danger menu items use colorError for the text and, on hover, as the fill under colorTextLightSolid.
          colorError: "#ff7875",
          colorTextLightSolid: "#0d1117",
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
          separatorColor: "#7d8590",
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
          // antd puts colorPrimary text on the active page (1.8:1 on #21262d); the accent stays on its border.
          itemActiveColor: "#e6edf3",
          itemActiveColorHover: "#e6edf3",
        },
      },
    };
  }

  return {
    algorithm: theme.defaultAlgorithm,
    token: {
      colorPrimary,
      // Descriptions and secondary text: antd's default (0.45 alpha) falls below 4.5:1.
      colorTextDescription: "rgba(0, 0, 0, 0.65)",
      // Links follow the accent (antd defaults to its own blue); blue's accent is too light for text.
      colorLink: isTerrakube ? colorPrimary : "#0958d9",
      // Hover/active steps of both link palettes are below 4.5:1 on white (purple #b37feb is 2.9:1).
      colorLinkHover: isTerrakube ? "#531dab" : "#0958d9",
      colorLinkActive: isTerrakube ? "#531dab" : "#0958d9",
      // Tertiary text (0.45 alpha) is 3.4:1 on white.
      colorTextTertiary: "rgba(0, 0, 0, 0.55)",
      // Select arrows and other quaternary icons (0.25 alpha) are 1.8:1; placeholders follow quaternary by default.
      colorTextQuaternary: "rgba(0, 0, 0, 0.45)",
      colorTextPlaceholder: "rgba(0, 0, 0, 0.55)",
      // Disabled text keeps the faint grey so disabled controls still read as disabled.
      colorTextDisabled: "rgba(0, 0, 0, 0.25)",
    },
    components: {
      Avatar: {
        // Default avatars: white glyph on antd's placeholder grey is 1.8:1.
        colorTextPlaceholder: "#737373",
      },
      Menu: {
        darkItemBg: "#161b22",
        darkPopupBg: "#161b22",
        darkSubMenuItemBg: "#161b22",
        darkItemSelectedBg: isTerrakube ? "#2d1548" : "#0d2942",
        darkItemHoverBg: isTerrakube ? "#2a1f3d" : "#16232f",
        // Danger items ("Delete module"): antd's colorError is 3.3:1 on white.
        dangerItemColor: "#cf1322",
        dangerItemHoverColor: "#cf1322",
        dangerItemSelectedColor: "#cf1322",
      },
      Dropdown: {
        // Danger menu items use colorError for the text and, on hover, as the fill under white text.
        colorError: "#cf1322",
      },
      Layout: {
        headerBg: "#1e2837",
        siderBg: "#161b22",
      },
    },
  };
};

export const defaultColorScheme: ColorSchemeOption = "terrakube";
export const defaultThemeMode: ThemeMode = "light";

// Export a default theme configuration using the default color scheme and theme mode
export const themeConfig = getThemeConfig(defaultColorScheme, defaultThemeMode);
