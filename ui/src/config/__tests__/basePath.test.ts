import { getPostLoginTarget, resolvePostLoginTarget } from "../basePath";

const setEnv = (basePath?: string) => {
  window._env_ = {
    REACT_APP_AUTHORITY: "https://auth.example.com/dex",
    REACT_APP_CLIENT_ID: "terrakube-ui",
    REACT_APP_REDIRECT_URI: "",
    REACT_APP_SCOPE: "openid profile email",
    REACT_APP_TERRAKUBE_API_URL: "https://api.example.com/api/v1/",
    REACT_APP_TERRAKUBE_VERSION: "test",
    REACT_APP_REGISTRY_URI: "https://registry.example.com",
    ...(basePath === undefined ? {} : { REACT_APP_BASE_PATH: basePath }),
  };
};

const runPath = "/organizations/org-1/workspaces/ws-1/runs/run-1";

afterEach(() => window.history.replaceState({}, "", "/"));

describe("getPostLoginTarget", () => {
  it("captures the path, query and fragment of the page being opened", () => {
    window.history.pushState({}, "", `${runPath}?tab=logs#step-2`);

    expect(getPostLoginTarget()).toBe(`${runPath}?tab=logs#step-2`);
  });
});

describe("resolvePostLoginTarget", () => {
  describe("at the root base path", () => {
    beforeEach(() => setEnv());

    it("returns to the deep link the user opened before signing in", () => {
      expect(resolvePostLoginTarget(`${runPath}?tab=logs#step-2`)).toBe(`${runPath}?tab=logs#step-2`);
    });

    it.each([
      ["no state", undefined],
      ["a non-string state", { path: runPath }],
      ["an empty state", ""],
      ["a relative path", "organizations/org-1"],
      ["a protocol-relative URL", "//evil.example.com/organizations"],
      ["a backslash protocol-relative URL", "/\\evil.example.com/organizations"],
      ["an absolute URL on another origin", "https://evil.example.com/organizations"],
      ["the absolute redirect URI older sessions stored", "http://localhost/"],
      ["a javascript URL", "javascript:alert(1)"],
      ["a path that normalizes to a protocol-relative URL", "/..//evil.example.com/organizations"],
    ])("falls back to the base path for %s", (_label, state) => {
      expect(resolvePostLoginTarget(state)).toBe("/");
    });

    it("drops the sign-in callback parameters so the callback is not replayed", () => {
      expect(resolvePostLoginTarget(`${runPath}?code=abc&state=xyz&session_state=s&tab=logs`)).toBe(
        `${runPath}?tab=logs`
      );
    });
  });

  describe("under a sub path", () => {
    beforeEach(() => setEnv("/ui"));

    it("returns to a deep link inside the base path", () => {
      expect(resolvePostLoginTarget(`/ui${runPath}`)).toBe(`/ui${runPath}`);
    });

    it("returns the bare base path as the router basename", () => {
      expect(resolvePostLoginTarget("/ui?tab=logs")).toBe("/ui/?tab=logs");
    });

    it("drops the sign-in callback parameters", () => {
      expect(resolvePostLoginTarget(`/ui${runPath}?code=abc&state=xyz`)).toBe(`/ui${runPath}`);
    });

    it.each([
      ["a path outside the base path", runPath],
      ["a path that only shares the prefix", "/uix/organizations"],
    ])("falls back to the base path for %s", (_label, state) => {
      expect(resolvePostLoginTarget(state)).toBe("/ui/");
    });
  });
});
