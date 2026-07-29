import { getThemeConfig, defaultColorScheme } from "../themeConfig";

describe("themeConfig", () => {
  it("defaults to the terrakube (purple) color scheme", () => {
    expect(defaultColorScheme).toBe("terrakube");
  });

  it("uses a fixed dark sidebar background in light mode", () => {
    const config = getThemeConfig("blue", "light");
    expect(config.components?.Layout?.siderBg).toBe("#161b22");
  });

  it("uses the same fixed dark sidebar background in dark mode", () => {
    const config = getThemeConfig("blue", "dark");
    expect(config.components?.Layout?.siderBg).toBe("#161b22");
  });

  it("applies terrakube purple as colorPrimary when that scheme is selected", () => {
    const config = getThemeConfig("terrakube", "light");
    expect(config.token?.colorPrimary).toBe("#722ED1");
  });

  it("applies blue as colorPrimary when the blue scheme is selected", () => {
    const config = getThemeConfig("blue", "light");
    expect(config.token?.colorPrimary).toBe("#1890ff");
  });
});
