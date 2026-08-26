export default function (source: string): string {
  if (source.endsWith(".git")) {
    source = source.replace(".git", "");
  }

  if (source.startsWith("git@")) {
    // Convert "git@host:path" -> "https://host/path" in one step. The previous
    // two-step rewrite ("git@" -> "https://") left the colon between host and
    // path intact, producing "https://host:path", which is only well-formed
    // for the four hardcoded providers below (each one runs a host-specific
    // ".com:"/".org:" -> "/" replacement). For any other host -- including
    // self-hosted git over SSH and hosts that contain a hyphen (e.g.
    // github-test.com) -- that left "https://github-test.com:terrakube" and
    // `new URL(...)` interpreted "terrakube" as a port, which crashed the
    // organization page (issue #3168, bug 2).
    source = source.replace(/^git@([^:]+):/, "https://$1/");
  }

  if (source.includes("dev.azure.com")) {
    // ":v3" handles HTTPS-shaped DevOps URLs that still carry the v3 segment;
    // "/v3" handles the SSH form after the regex above rewrote the colon.
    source = source.replace(":v3", "").replace("/v3", "").replace("ssh.", "");
    source = stripUserFromHost(source);
    source = source.replace("/_git", "");
  }

  if (source.includes("github.com") || source.includes("ghe.com")) {
    source = source.replace(".com:", ".com/");
  }

  if (source.includes("bitbucket.org")) {
    source = source.replace(".org:", ".org/");
    source = stripUserFromHost(source);
  }

  if (source.includes("gitlab.com")) {
    source = source.replace(".com:", ".com/");
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
