import { getStoredSidebarCollapsed, setStoredSidebarCollapsed } from "../sidebarPreference";

const STORAGE_KEY = "terrakube.sidebarCollapsed";

describe("sidebarPreference", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("defaults to not collapsed when nothing is stored", () => {
    expect(getStoredSidebarCollapsed()).toBe(false);
  });

  it("returns true when 'true' is stored", () => {
    localStorage.setItem(STORAGE_KEY, "true");
    expect(getStoredSidebarCollapsed()).toBe(true);
  });

  it("returns false when an invalid value is stored", () => {
    localStorage.setItem(STORAGE_KEY, "garbage");
    expect(getStoredSidebarCollapsed()).toBe(false);
  });

  it("setStoredSidebarCollapsed writes the value under the expected key", () => {
    setStoredSidebarCollapsed(true);
    expect(localStorage.getItem(STORAGE_KEY)).toBe("true");

    setStoredSidebarCollapsed(false);
    expect(localStorage.getItem(STORAGE_KEY)).toBe("false");
  });
});
