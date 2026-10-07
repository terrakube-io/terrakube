import { VcsConnectionType, VcsType, VcsTypeExtended } from "../types";

// Provider facts shared by the VCS list, add and edit pages.

export const VCS_LABELS: Record<string, string> = {
  GITHUB: "GitHub",
  GITHUB_APP: "GitHub App",
  GITHUB_ENTERPRISE: "GitHub Enterprise",
  GITLAB: "GitLab",
  GITLAB_ENTERPRISE: "GitLab Enterprise",
  GITLAB_COMMUNITY: "GitLab Community Edition",
  BITBUCKET: "Bitbucket",
  BITBUCKET_SERVER: "Bitbucket Server",
  AZURE_DEVOPS: "Azure DevOps",
  AZURE_DEVOPS_SERVER: "Azure DevOps Server",
};

export const vcsLabel = (vcs: VcsTypeExtended) => VCS_LABELS[vcs] ?? "GitHub";

const DOCS = "https://docs.terrakube.io/user-guide/vcs-providers";

export const getDocsUrl = (vcs: VcsTypeExtended): string => {
  switch (vcs) {
    case "GITLAB":
      return `${DOCS}/gitlab.com`;
    case "GITLAB_ENTERPRISE":
    case "GITLAB_COMMUNITY":
      return `${DOCS}/gitlab-ee-and-ce`;
    case "BITBUCKET":
      return `${DOCS}/bitbucket.com`;
    case "BITBUCKET_SERVER":
      return `${DOCS}/bitbucket-server`;
    case "AZURE_DEVOPS":
    case "AZURE_DEVOPS_SERVER":
      return `${DOCS}/azure-devops`;
    case "GITHUB_ENTERPRISE":
      return `${DOCS}/github-enterprise`;
    case "GITHUB_APP":
      return `${DOCS}/github-app`;
    default:
      return `${DOCS}/github.com`;
  }
};

// The API's provider type for each picker option.
export const getVcsType = (vcs: VcsTypeExtended): VcsType => {
  switch (vcs) {
    case "GITLAB":
    case "GITLAB_ENTERPRISE":
    case "GITLAB_COMMUNITY":
      return VcsType.GITLAB;
    case "BITBUCKET":
    case "BITBUCKET_SERVER":
      return VcsType.BITBUCKET;
    case "AZURE_DEVOPS":
    case "AZURE_DEVOPS_SERVER":
      return VcsType.AZURE_SP_MI;
    default:
      return VcsType.GITHUB;
  }
};

const DEFAULT_ENDPOINTS: Partial<Record<VcsType, string>> = {
  [VcsType.GITHUB]: "https://github.com",
  [VcsType.GITLAB]: "https://gitlab.com",
  [VcsType.BITBUCKET]: "https://bitbucket.org",
  [VcsType.AZURE_SP_MI]: "https://app.vssps.visualstudio.com",
};

export const getVcsTypeExtended = (
  vcsType: VcsType,
  connectionType: VcsConnectionType,
  endpoint: string | null | undefined
): VcsTypeExtended => {
  const ep = endpoint ?? "";
  const isDefault = !ep || ep === (DEFAULT_ENDPOINTS[vcsType] ?? "");

  switch (vcsType) {
    case VcsType.GITHUB:
      if (connectionType === VcsConnectionType.STANDALONE) {
        return isDefault ? VcsTypeExtended.GITHUB_APP : VcsTypeExtended.GITHUB_ENTERPRISE;
      }
      return isDefault ? VcsTypeExtended.GITHUB : VcsTypeExtended.GITHUB_ENTERPRISE;
    case VcsType.GITLAB:
      return isDefault ? VcsTypeExtended.GITLAB : VcsTypeExtended.GITLAB_ENTERPRISE;
    case VcsType.BITBUCKET:
      return isDefault ? VcsTypeExtended.BITBUCKET : VcsTypeExtended.BITBUCKET_SERVER;
    case VcsType.AZURE_SP_MI:
    case VcsType.AZURE_DEVOPS:
      return isDefault ? VcsTypeExtended.AZURE_DEVOPS : VcsTypeExtended.AZURE_DEVOPS_SERVER;
    default:
      return VcsTypeExtended.GITHUB;
  }
};

// Cloud-hosted providers use fixed URLs; self-hosted ones ask for them.
export const usesFixedUrls = (vcs: VcsTypeExtended): boolean =>
  ["GITHUB", "GITHUB_APP", "GITLAB", "BITBUCKET", "AZURE_DEVOPS"].includes(vcs);

export const getClientIdName = (vcs: VcsTypeExtended, connectionType: VcsConnectionType): string => {
  switch (vcs) {
    case "GITLAB":
    case "GITLAB_ENTERPRISE":
    case "GITLAB_COMMUNITY":
      return "Application ID";
    case "BITBUCKET":
    case "BITBUCKET_SERVER":
      return "Key";
    case "AZURE_DEVOPS":
    case "AZURE_DEVOPS_SERVER":
      return "Managed identity app ID";
    default:
      return connectionType === VcsConnectionType.OAUTH ? "Client ID" : "App ID";
  }
};

export const getSecretIdName = (vcs: VcsTypeExtended, connectionType: VcsConnectionType): string => {
  switch (vcs) {
    case "GITLAB":
    case "GITLAB_ENTERPRISE":
    case "GITLAB_COMMUNITY":
    case "BITBUCKET":
    case "BITBUCKET_SERVER":
      return "Secret";
    case "AZURE_DEVOPS":
    case "AZURE_DEVOPS_SERVER":
      return "Client secret";
    default:
      return connectionType === VcsConnectionType.OAUTH ? "Client secret" : "Private key (PKCS#8)";
  }
};

export const validatePrivateKeyFormat = (_: unknown, value: string) => {
  if (!value) return Promise.resolve();
  if (!value.includes("-----BEGIN PRIVATE KEY-----")) {
    return Promise.reject(new Error("Private key must be in PKCS#8 format (-----BEGIN PRIVATE KEY-----)"));
  }
  if (!value.includes("-----END PRIVATE KEY-----")) {
    return Promise.reject(new Error("Private key is incomplete (missing -----END PRIVATE KEY-----)"));
  }
  return Promise.resolve();
};

export const validateUrlFormat = (_: unknown, value: string) => {
  if (!value) return Promise.resolve();
  try {
    const url = new URL(value);
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return Promise.reject(new Error("URL must start with http:// or https://"));
    }
    return Promise.resolve();
  } catch {
    return Promise.reject(new Error("Enter a valid URL, for example https://git.example.com"));
  }
};

export const getApiOrigin = () => new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;

export const getCallbackUrl = (callbackId: string) => `${getApiOrigin()}/callback/v1/vcs/${callbackId}`;

// Where the OAuth flow starts for a provider; endpoint overrides the public host.
export const getConnectUrl = (vcsType: VcsType, clientId: string, callbackUrl: string, endpoint?: string | null) => {
  switch (vcsType) {
    case VcsType.GITLAB:
      return `${endpoint ?? "https://gitlab.com"}/oauth/authorize?client_id=${clientId}&response_type=code&scope=api&&redirect_uri=${callbackUrl}`;
    case VcsType.BITBUCKET:
      return `${endpoint ?? "https://bitbucket.org"}/site/oauth2/authorize?client_id=${clientId}&response_type=code&response_type=code&scope=repository`;
    case VcsType.AZURE_DEVOPS:
      return `${endpoint ?? "https://app.vssps.visualstudio.com"}/oauth2/authorize?client_id=${clientId}&redirect_uri=${callbackUrl}&response_type=Assertion&scope=vso.code+vso.code_status`;
    default:
      return `${endpoint ?? "https://github.com"}/login/oauth/authorize?client_id=${clientId}&allow_signup=false&scope=repo`;
  }
};

// GitHub Apps and Azure managed identities never go through the OAuth flow.
export const usesOAuthFlow = (vcsType: VcsType, connectionType: VcsConnectionType) =>
  connectionType === VcsConnectionType.OAUTH && vcsType !== VcsType.AZURE_SP_MI;
