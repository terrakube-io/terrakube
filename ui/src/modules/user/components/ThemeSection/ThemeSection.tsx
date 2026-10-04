import { ColorSchemeOption, ThemeMode } from "../../../../config/themeConfig";
import { useTheme } from "../../../../context/ThemeContext";
import "./ThemeSection.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";

const SCHEME_COLORS: Record<ColorSchemeOption, string> = {
  terrakube: "#722ED1",
  blue: "#1890ff",
};

const colorOptions: { value: ColorSchemeOption; title: string; note: string }[] = [
  { value: "terrakube", title: "Terrakube", note: "Default. Uses the main Terrakube logo colors" },
  { value: "blue", title: "Blue", note: "The classic Terrakube theme" },
];

// Surface colors of each mode, used only to draw the miniature preview.
const MODE_PREVIEW: Record<ThemeMode, { canvas: string; card: string; line: string; ink: string }> = {
  light: { canvas: "#f0f2f5", card: "#ffffff", line: "#d9d9d9", ink: "rgba(0, 0, 0, 0.88)" },
  dark: { canvas: "#0d1117", card: "#161b22", line: "#30363d", ink: "#e6edf3" },
};

const themeModeOptions: { value: ThemeMode; title: string }[] = [
  { value: "light", title: "Light" },
  { value: "dark", title: "Dark" },
];

const ThemePreview = ({ mode, accent }: { mode: ThemeMode; accent: string }) => {
  const { canvas, card, line, ink } = MODE_PREVIEW[mode];
  return (
    <span className="theme-preview" aria-hidden="true" style={{ background: canvas }}>
      <span className="theme-preview-side">
        <span className="theme-preview-nav" style={{ background: accent }} />
      </span>
      <span className="theme-preview-main">
        <span className="theme-preview-card" style={{ background: card, borderColor: line }}>
          <span className="theme-preview-line" style={{ background: ink }} />
          <span className="theme-preview-line theme-preview-line-short" style={{ background: ink }} />
          <span className="theme-preview-button" style={{ background: accent }} />
        </span>
      </span>
    </span>
  );
};

export const ThemeSection = () => {
  const { colorScheme, themeMode, setColorScheme, setThemeMode } = useTheme();
  const accent = SCHEME_COLORS[colorScheme];

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
              <span className="theme-swatch" style={{ background: SCHEME_COLORS[option.value] }} />
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
              <ThemePreview mode={option.value} accent={accent} />
              <span className="theme-tile-title">{option.title}</span>
            </label>
          ))}
        </div>
      </fieldset>
    </div>
  );
};
