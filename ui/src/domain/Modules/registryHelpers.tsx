import { CloudOutlined } from "@ant-design/icons";
import { FaAws, FaGoogle } from "@/config/iconList";
import { VscAzure } from "react-icons/vsc";

// Logos take the text color of their tile so they stay visible in both themes.
export function providerIcon(provider?: string) {
  switch (provider?.toLowerCase()) {
    case "azurerm":
    case "azure":
      return <VscAzure />;
    case "aws":
      return <FaAws />;
    case "google":
    case "gcp":
      return <FaGoogle />;
    default:
      return <CloudOutlined />;
  }
}

export function registryHostname(): string {
  try {
    return new URL(window._env_.REACT_APP_REGISTRY_URI).hostname;
  } catch {
    return "registry.example.com";
  }
}

/**
 * Imported providers store their repository in the description ("Source: https://github.com/owner/repo").
 * Returns the description without it, and the URL with its owner/repo label.
 */
export function parseProviderDescription(description = "") {
  const match = description.match(/https?:\/\/[^\s]+/)?.[0];
  const text = (match ? description.replace(match, "") : description).replace(/Source:?\s*/i, "").trim();
  // A clone URL ("…/repo.git") is not a page; links such as "/issues" are built on top of it.
  const url = match?.replace(/\/+$/, "").replace(/\.git$/, "");
  let label = "";
  if (url) {
    try {
      label = new URL(url).pathname.replace(/^\//, "");
    } catch {
      /* invalid URL – show none */
    }
  }
  return { text, source: url && label ? { url, label } : undefined };
}

/** Newest first: "1.10.0" before "1.9.0", a release before its pre-releases, a leading "v" ignored. */
export function compareVersionsDesc(a: string, b: string) {
  const [coreA, preA] = a.replace(/^v/, "").split(/-(.*)/);
  const [coreB, preB] = b.replace(/^v/, "").split(/-(.*)/);
  const numeric = { numeric: true };
  const byCore = coreB.localeCompare(coreA, undefined, numeric);
  if (byCore !== 0 || preA === preB) return byCore;
  if (!preA) return -1;
  if (!preB) return 1;
  return preB.localeCompare(preA, undefined, numeric);
}
