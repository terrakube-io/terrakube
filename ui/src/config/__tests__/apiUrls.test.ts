import { apiPrefix, apiUrl, graphqlUrl, publicHostname, subscriptionsUrl } from "../apiUrls";

const setEnv = (extra: Record<string, string> = {}, apiUrlValue = "https://api.example.com/api/v1/") => {
  window._env_ = {
    REACT_APP_AUTHORITY: "https://auth.example.com/dex",
    REACT_APP_CLIENT_ID: "terrakube-ui",
    REACT_APP_REDIRECT_URI: "https://ui.example.com",
    REACT_APP_SCOPE: "openid profile email",
    REACT_APP_TERRAKUBE_API_URL: apiUrlValue,
    REACT_APP_TERRAKUBE_VERSION: "test",
    REACT_APP_REGISTRY_URI: "https://registry.example.com",
    ...extra,
  };
};

describe("apiUrls", () => {
  describe("without a public URL", () => {
    beforeEach(() => setEnv());

    it("keeps legacy paths unchanged", () => {
      expect(apiPrefix()).toBe("");
      expect(apiUrl("/tfstate/v1/x")).toBe("https://api.example.com/tfstate/v1/x");
      expect(graphqlUrl()).toBe("https://api.example.com/graphql/api/v1");
      expect(subscriptionsUrl()).toBe("wss://api.example.com/subscriptions");
      expect(publicHostname()).toBe("api.example.com");
    });

    it("uses ws for http origins", () => {
      setEnv({}, "http://localhost:8080/api/v1/");
      expect(subscriptionsUrl()).toBe("ws://localhost:8080/subscriptions");
    });

    it("treats an empty public URL as unset", () => {
      setEnv({ REACT_APP_TERRAKUBE_PUBLIC_URL: "" });
      expect(apiUrl("/tfstate/v1/x")).toBe("https://api.example.com/tfstate/v1/x");
    });
  });

  describe("with a public URL", () => {
    beforeEach(() =>
      setEnv(
        { REACT_APP_TERRAKUBE_PUBLIC_URL: "https://terrakube.example.com" },
        "https://terrakube.example.com/api/v1/"
      )
    );

    it("prefixes legacy paths with /api", () => {
      expect(apiPrefix()).toBe("/api");
      expect(apiUrl("/tfstate/v1/x")).toBe("https://terrakube.example.com/api/tfstate/v1/x");
      expect(graphqlUrl()).toBe("https://terrakube.example.com/api/graphql/api/v1");
      expect(subscriptionsUrl()).toBe("wss://terrakube.example.com/api/subscriptions");
      expect(publicHostname()).toBe("terrakube.example.com");
    });
  });
});
