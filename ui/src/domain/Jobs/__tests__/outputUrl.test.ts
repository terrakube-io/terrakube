import { getJobOutputRequestUrl, getPublicApiOrigin, isTerrakubeApiUrl } from "../outputUrl";

describe("outputUrl helpers", () => {
  beforeEach(() => {
    window._env_ = {
      REACT_APP_AUTHORITY: "https://auth.example.com/dex",
      REACT_APP_CLIENT_ID: "terrakube-ui",
      REACT_APP_REDIRECT_URI: "https://ui.example.com",
      REACT_APP_SCOPE: "openid profile email",
      REACT_APP_TERRAKUBE_API_URL: "https://api.example.com/api/v1/",
      REACT_APP_TERRAKUBE_VERSION: "test",
      REACT_APP_REGISTRY_URI: "https://registry.example.com",
    };
  });

  it("returns the public API origin from the UI env", () => {
    expect(getPublicApiOrigin()).toBe("https://api.example.com");
  });

  it("rewrites internal tfoutput URLs to the public API origin", () => {
    const outputUrl =
      "http://api.railway.internal:8080/tfoutput/v1/organization/org-id/job/30/step/step-id?download=false";

    expect(getJobOutputRequestUrl(outputUrl)).toBe(
      "https://api.example.com/tfoutput/v1/organization/org-id/job/30/step/step-id?download=false"
    );
  });

  it("resolves relative tfoutput paths against the public API origin", () => {
    expect(getJobOutputRequestUrl("/tfoutput/v1/organization/org-id/job/30/step/step-id")).toBe(
      "https://api.example.com/tfoutput/v1/organization/org-id/job/30/step/step-id"
    );
  });

  it("leaves external non-Terrakube URLs untouched", () => {
    const outputUrl = "https://storage.example.com/logs/step-id.txt";

    expect(getJobOutputRequestUrl(outputUrl)).toBe(outputUrl);
    expect(isTerrakubeApiUrl(outputUrl)).toBe(false);
  });

  it("detects public Terrakube URLs after rewriting", () => {
    const outputUrl = getJobOutputRequestUrl("http://api.railway.internal:8080/tfoutput/v1/organization/org-id/job/30");

    expect(isTerrakubeApiUrl(outputUrl)).toBe(true);
  });
});
