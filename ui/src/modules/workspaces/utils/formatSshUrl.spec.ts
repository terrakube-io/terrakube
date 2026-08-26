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

  test("self-hosted SSH url with hyphenated host is formatted correctly", () => {
    // Regression test for issue #3168: a host containing a hyphen (here a
    // self-hosted GitHub instance) used to round-trip through the function as
    // "https://github-test.com:terrakube-io/terrakube-docker-compose", which
    // crashed `new URL(...)` because "terrakube-io" parses as a port. The host
    // does not match any of the four hardcoded provider blocks, so the colon
    // has to be normalized in the generic git@ rewrite step.
    const result = formatSshUrl("git@github-test.com:terrakube-io/terrakube-docker-compose.git");
    expect(result).toBe("https://github-test.com/terrakube-io/terrakube-docker-compose");
  });
});
