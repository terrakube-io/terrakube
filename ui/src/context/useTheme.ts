import { createContext, useContext } from "react";
import { ColorSchemeOption, ThemeMode } from "../config/themeConfig";

export interface ThemeContextType {
  colorScheme: ColorSchemeOption;
  themeMode: ThemeMode;
  setColorScheme: (scheme: ColorSchemeOption) => void;
  setThemeMode: (mode: ThemeMode) => void;
}

// Lives apart from ThemeProvider so the provider file only exports a component (fast refresh).
export const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export const useTheme = () => {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error("useTheme must be used within a ThemeProvider");
  }

  return context;
};
