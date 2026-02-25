import { Card, Flex, Select, Space, Typography } from "antd";
import { ColorSchemeOption, ThemeMode } from "../../../../config/themeConfig";
import { useTheme } from "../../../../context/ThemeContext";
import "./ThemeSection.css";

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
      value: "default",
      color: "#1890ff",
      label: "Default (The classic Terrakube theme)",
    },
    {
      value: "terrakube",
      color: "#722ED1",
      label: "Terrakube (Uses the main Terrakube logo colors)",
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
      <Flex gap="middle" justify="space-between" align="center">
        <Flex vertical>
          <Typography.Title className="title">Theme Settings</Typography.Title>
          <Typography.Text type="secondary">
            Customize the appearance of Terrakube by selecting your preferred color scheme and theme mode.
          </Typography.Text>
        </Flex>
      </Flex>

      <Card className="theme-card">
        <Space direction="vertical" size="large" style={{ width: "100%" }}>
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
      </Card>
    </div>
  );
};
