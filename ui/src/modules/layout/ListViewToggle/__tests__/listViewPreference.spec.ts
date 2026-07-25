import { getStoredListViewMode, setStoredListViewMode } from "../listViewPreference";

const STORAGE_KEY = "terrakube.listViewMode";

describe("listViewPreference", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("defaults to 'new' when nothing is stored", () => {
    expect(getStoredListViewMode()).toBe("new");
  });

  it("returns 'legacy' when 'legacy' is stored", () => {
    localStorage.setItem(STORAGE_KEY, "legacy");
    expect(getStoredListViewMode()).toBe("legacy");
  });

  it("returns 'new' when an invalid value is stored", () => {
    localStorage.setItem(STORAGE_KEY, "garbage");
    expect(getStoredListViewMode()).toBe("new");
  });

  it("setStoredListViewMode writes the value under the expected key", () => {
    setStoredListViewMode("legacy");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("legacy");

    setStoredListViewMode("new");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("new");
  });
});
