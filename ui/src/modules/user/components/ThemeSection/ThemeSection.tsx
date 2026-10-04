import { ColorSchemeOption, ThemeMode } from "../../../../config/themeConfig";
import { useTheme } from "../../../../context/ThemeContext";
import "./ThemeSection.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";

const colorOptions: { value: ColorSchemeOption; title: string; note: string }[] = [
  { value: "terrakube", title: "Terrakube", note: "Default. Uses the main Terrakube logo colors" },
  { value: "blue", title: "Blue", note: "The classic Terrakube theme" },
];

const themeModeOptions: { value: ThemeMode; title: string }[] = [
  { value: "light", title: "Light" },
  { value: "dark", title: "Dark" },
];

// Miniature of the given mode; its surface colors come from ThemeSection.css, the accent is the active scheme's.
const ThemePreview = ({ mode }: { mode: ThemeMode }) => (
  <span className="theme-preview" aria-hidden="true" data-mode={mode}>
    <span className="theme-preview-side">
      <span className="theme-preview-nav" />
    </span>
    <span className="theme-preview-main">
      <span className="theme-preview-card">
        <span className="theme-preview-line" />
        <span className="theme-preview-line theme-preview-line-short" />
        <span className="theme-preview-button" />
      </span>
    </span>
  </span>
);

export const ThemeSection = () => {
  const { colorScheme, themeMode, setColorScheme, setThemeMode } = useTheme();

  return (
    <div className="theme-section">
      <SettingsPageHeader
        title="Theme Settings"
        description="Customize the appearance of Terrakube by selecting your preferred color scheme and theme mode."
      />
      <fieldset className="theme-group">
        <legend className="theme-legend">Color scheme</legend>
        <div className="theme-tiles">
          {colorOptions.map((option) => (
            <label key={option.value} className="theme-tile" data-checked={colorScheme === option.value}>
              <input
                type="radio"
                name="theme-color-scheme"
                checked={colorScheme === option.value}
                onChange={() => setColorScheme(option.value)}
              />
              <span className="theme-swatch" data-scheme={option.value} />
              <span className="theme-tile-title">{option.title}</span>
              <span className="theme-tile-note">{option.note}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset className="theme-group">
        <legend className="theme-legend">Theme mode</legend>
        <div className="theme-tiles">
          {themeModeOptions.map((option) => (
            <label
              key={option.value}
              className="theme-tile theme-tile-preview"
              data-checked={themeMode === option.value}
            >
              <input
                type="radio"
                name="theme-mode"
                checked={themeMode === option.value}
                onChange={() => setThemeMode(option.value)}
              />
              <ThemePreview mode={option.value} />
              <span className="theme-tile-title">{option.title}</span>
            </label>
          ))}
        </div>
      </fieldset>
    </div>
  );
};
