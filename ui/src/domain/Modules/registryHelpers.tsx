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
  const url = description.match(/https?:\/\/[^\s]+/)?.[0];
  const text = (url ? description.replace(url, "") : description).replace(/Source:?\s*/i, "").trim();
  let label = "";
  if (url) {
    try {
      label = new URL(url).pathname.replace(/^\//, "").replace(/\.git$/, "");
    } catch {
      /* invalid URL – show none */
    }
  }
  return { text, source: url && label ? { url, label } : undefined };
}
