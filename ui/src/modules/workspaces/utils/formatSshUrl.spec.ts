import formatSshUrl from "./formatSshUrl";

describe("formatSshUrl", () => {
  test("DevOps SSH url is formattted correctly", () => {
    const result = formatSshUrl("git@ssh.dev.azure.com:v3/org-name/project-name/repo-name");
    expect(result).toBe("https://dev.azure.com/org-name/project-name/repo-name");
  });

  test("DevOps HTTPS url is formattted correctly", () => {
    const result = formatSshUrl("https://org-name@dev.azure.com/org-name/project-name/_git/repo-name");
    expect(result).toBe("https://dev.azure.com/org-name/project-name/repo-name");
  });

  test("GitHub SSH url is formattted correctly", () => {
    const result = formatSshUrl("git@github.com:org-name/repo-name.git");
    expect(result).toBe("https://github.com/org-name/repo-name");
  });

  test("BitBucket HTTPS url is formatted correctly", () => {
    const result = formatSshUrl("https://username@bitbucket.org/org-name/repo-name.git");
    expect(result).toBe("https://bitbucket.org/org-name/repo-name");
  });

  test("BitBucket SSH url is formatted correctly", () => {
    const result = formatSshUrl("git@bitbucket.org:org-name/repo-name.git");
    expect(result).toBe("https://bitbucket.org/org-name/repo-name");
  });

  test("GitLab HTTPS url is formatted correctly", () => {
    const result = formatSshUrl("https://gitlab.com/org-name/project-name/repo-name.git");
    expect(result).toBe("https://gitlab.com/org-name/project-name/repo-name");
  });

  test("GitLab SSH url is formatted correctly", () => {
    const result = formatSshUrl("git@gitlab.com:org-name/project-name/repo-name.git");
    expect(result).toBe("https://gitlab.com/org-name/project-name/repo-name");
  });

  // Self-hosted instances (issue #2925)
  test("Self-hosted GitLab SSH url is formatted correctly", () => {
    const result = formatSshUrl("git@gitlab.example.com:org-name/repo-name.git");
    expect(result).toBe("https://gitlab.example.com/org-name/repo-name");
  });

  test("Self-hosted GitHub Enterprise SSH url is formatted correctly", () => {
    const result = formatSshUrl("git@github.mycompany.com:org-name/repo-name.git");
    expect(result).toBe("https://github.mycompany.com/org-name/repo-name");
  });

  test("Self-hosted Gitea SSH url is formatted correctly", () => {
    const result = formatSshUrl("git@gitea.internal.dev:team/project.git");
    expect(result).toBe("https://gitea.internal.dev/team/project");
  });

  test("Self-hosted SSH url with nested path is formatted correctly", () => {
    const result = formatSshUrl("git@git.company.io:group/subgroup/repo-name.git");
    expect(result).toBe("https://git.company.io/group/subgroup/repo-name");
  });

  test("Self-hosted HTTPS url with credentials is formatted correctly", () => {
    const result = formatSshUrl("https://user@gitlab.example.com/org-name/repo-name.git");
    expect(result).toBe("https://gitlab.example.com/org-name/repo-name");
  });

  test("Plain HTTPS url is returned unchanged", () => {
    const result = formatSshUrl("https://github.com/org-name/repo-name");
    expect(result).toBe("https://github.com/org-name/repo-name");
  });
});
