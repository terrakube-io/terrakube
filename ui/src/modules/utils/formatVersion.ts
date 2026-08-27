const EXACT_VERSION = /^\d+(\.\d+)*$/;

export default function formatVersion(version: string) {
  const trimmed = version.trim();
  const stripped = trimmed.replace(/^v/i, "");
  return EXACT_VERSION.test(stripped) ? `v${stripped}` : trimmed;
}
