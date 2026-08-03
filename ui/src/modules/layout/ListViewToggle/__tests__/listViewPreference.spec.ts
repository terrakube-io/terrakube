import { getStoredListViewMode, setStoredListViewMode } from "../listViewPreference";

const STORAGE_KEY = "terrakube.listViewMode";

describe("listViewPreference", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("defaults to 'compact' when nothing is stored", () => {
    expect(getStoredListViewMode()).toBe("compact");
  });

  it("returns 'cards' when 'cards' is stored", () => {
    localStorage.setItem(STORAGE_KEY, "cards");
    expect(getStoredListViewMode()).toBe("cards");
  });

  it("returns 'compact' when an invalid value is stored", () => {
    localStorage.setItem(STORAGE_KEY, "garbage");
    expect(getStoredListViewMode()).toBe("compact");
  });

  it("setStoredListViewMode writes the value under the expected key", () => {
    setStoredListViewMode("cards");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("cards");

    setStoredListViewMode("compact");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("compact");
  });
});
