import { Select, Space, Typography } from "antd";
import { ColorSchemeOption, ThemeMode } from "../../../../config/themeConfig";
import { useTheme } from "../../../../context/ThemeContext";
import "./ThemeSection.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";

const ColorBox = ({ color }: { color: string }) => (
  <span className="color-box" style={{ backgroundColor: color }}></span>
);

const ColorOption = ({ color, label }: { color: string; label: string }) => (
  <div className="color-option">
    <ColorBox color={color} />
    <span>{label}</span>
  </div>
);

export const ThemeSection = () => {
  const { colorScheme, themeMode, setColorScheme, setThemeMode } = useTheme();

  const handleColorSchemeChange = (value: ColorSchemeOption) => {
    setColorScheme(value);
  };

  const handleThemeModeChange = (value: ThemeMode) => {
    setThemeMode(value);
  };

  const colorOptions = [
    {
      value: "terrakube",
      color: "#722ED1",
      label: "Terrakube (Default — uses the main Terrakube logo colors)",
    },
    {
      value: "blue",
      color: "#1890ff",
      label: "Blue (The classic Terrakube theme)",
    },
  ];

  const themeModeOptions = [
    {
      value: "light",
      label: (
        <div className="color-option">
          <ColorBox color="#ffffff" />
          <span>Light</span>
        </div>
      ),
    },
    {
      value: "dark",
      label: (
        <div className="color-option">
          <ColorBox color="#000000" />
          <span>Dark</span>
        </div>
      ),
    },
  ];

  return (
    <div className="theme-section">
      <SettingsPageHeader
        title="Theme Settings"
        description="Customize the appearance of Terrakube by selecting your preferred color scheme and theme mode."
      />

      <Space orientation="vertical" size="large" style={{ width: "100%", maxWidth: 480 }}>
        <div>
          <Typography.Title level={5}>Color Scheme</Typography.Title>
          <Select
            value={colorScheme}
            onChange={handleColorSchemeChange}
            style={{ width: "100%" }}
            optionLabelProp="label"
            options={colorOptions.map((opt) => ({
              value: opt.value,
              label: <ColorOption color={opt.color} label={opt.value} />,
              children: <ColorOption color={opt.color} label={opt.label} />,
            }))}
          />
        </div>
        <div>
          <Typography.Title level={5}>Theme Mode</Typography.Title>
          <Select
            value={themeMode}
            onChange={handleThemeModeChange}
            style={{ width: "100%" }}
            options={themeModeOptions}
          />
        </div>
      </Space>
    </div>
  );
};
