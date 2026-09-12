import formatSshUrl from "@/modules/workspaces/utils/formatSshUrl";
import { VcsType } from "../../../domain/types";

export default function (normalizedSource: string): VcsType {
  const githubEndpoints = ["github.com", "ghe.com"];
  const devOpsEndpints = ["visualstudio.com", "dev.azure.com"];
  const gitlabEndpoints = ["gitlab.com"];
  const bitbucketEndpoints = ["bitbucket.org"];
  // Let's just be safe in case the wrong url was passed
  const fixedUrl = formatSshUrl(normalizedSource);
  let hostname: string;
  try {
    hostname = new URL(fixedUrl).hostname;
  } catch {
    return VcsType.UNKNOWN;
  }
  // ghe.com uses subdomain
  if (githubEndpoints.includes(hostname) || githubEndpoints.some((h) => hostname.endsWith(h))) return VcsType.GITHUB;
  // visualstudio.com uses subdomin
  if (devOpsEndpints.includes(hostname) || devOpsEndpints.some((h) => hostname.endsWith(h)))
    return VcsType.AZURE_DEVOPS;
  if (gitlabEndpoints.includes(hostname)) return VcsType.GITLAB;
  if (bitbucketEndpoints.includes(hostname)) return VcsType.BITBUCKET;
  return VcsType.UNKNOWN;
}
