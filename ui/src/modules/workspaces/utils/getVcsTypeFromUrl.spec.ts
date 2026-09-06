import { VcsType } from "../../../domain/types";
import getVcsTypeFromUrl from "./getVcsTypeFromUrl";

describe("getVcsTypeFromUrl", () => {
  test.each(["myorg/myrepo", "empty", "", "https://", "https://host:invalid/repo.git"])(
    "invalid source %j returns UNKNOWN",
    (source) => {
      expect(getVcsTypeFromUrl(source)).toBe(VcsType.UNKNOWN);
    }
  );

  test("SSH source returns the provider", () => {
    expect(getVcsTypeFromUrl("git@github.com:myorg/myrepo.git")).toBe(VcsType.GITHUB);
  });

  test("dev.azure.com returns correct", async () => {
    const result = getVcsTypeFromUrl("https://dev.azure.com/org-name/project-name/repo-name");
    expect(result).toBe(VcsType.AZURE_DEVOPS);
  });
  test("orgname.visualstudio.com returns correct", async () => {
    const result = getVcsTypeFromUrl("https://orgname.visualstudio.com/project-name/repo-name");
    expect(result).toBe(VcsType.AZURE_DEVOPS);
  });

  test("github.com returns correct", async () => {
    const result = getVcsTypeFromUrl("https://github.com/org-name/repo-name");
    expect(result).toBe(VcsType.GITHUB);
  });

  test("org.ghe.com returns correct", async () => {
    const result = getVcsTypeFromUrl("https://org.ghe.com/org-name/repo-name");
    expect(result).toBe(VcsType.GITHUB);
  });

  test("bitbucket.org returns correct", async () => {
    const result = getVcsTypeFromUrl("https://bitbucket.org/org-name/repo-name");
    expect(result).toBe(VcsType.BITBUCKET);
  });

  test("gitlab.com returns correct", async () => {
    const result = getVcsTypeFromUrl("https://gitlab.com/org-name/project-name/repo-name");
    expect(result).toBe(VcsType.GITLAB);
  });
});
