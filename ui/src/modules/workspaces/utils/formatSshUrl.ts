export default function (source: string): string {
  if (source.endsWith(".git")) {
    source = source.replace(".git", "");
  }

  // Azure DevOps SSH urls have a special format: git@ssh.dev.azure.com:v3/org/project/repo
  if (source.includes("dev.azure.com")) {
    source = source.replace(":v3", "").replace("ssh.", "");
    source = stripUserFromHost(source);
    source = source.replace("/_git", "");
    if (source.startsWith("git@")) {
      source = source.replace("git@", "https://");
    }
    return source;
  }

  // Generic SSH url handling: git@host:path → https://host/path
  // Works for github.com, gitlab.com, bitbucket.org, and any self-hosted instance
  if (source.startsWith("git@")) {
    source = source.replace(/^git@([^:]+):(.*)$/, "https://$1/$2");
    return source;
  }

  // HTTPS urls with embedded credentials: https://user@host/path → https://host/path
  if (source.includes("@") && source.startsWith("https://")) {
    source = stripUserFromHost(source);
  }

  return source;
}

function stripUserFromHost(source: string): string {
  const atIndex = source.indexOf("@");
  if (atIndex !== -1) {
    source = `https://${source.substring(atIndex + 1, source.length)}`;
  }
  return source;
}
