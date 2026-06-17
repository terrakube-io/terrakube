const VERSION_CONSTRAINT_PATTERNS = [
  /^\d+(\.\d+)*$/, // plain: 1.2.3
  /^(\d+|\*)(\.((\d+)|[xX*])(\.((\d+)|[xX*]))?)?$/, // npm x-range: 1.2.x, * (bare x/X unsupported by terraform-spring-boot-starter)
  /^~\d+(\.\d+(\.\d+)?)?$/, // npm tilde: ~1.2.3
  /^\^\d+(\.\d+(\.\d+)?)?$/, // npm caret: ^1.2.3
  /^\d+(\.\d+)*\s+-\s+\d+(\.\d+)*$/, // npm hyphen: 1.2.3 - 2.3.4
  /^(~>|>=?|<=?|!=|=)\s*\d+(\.\d+)*(\s+(~>|>=?|<=?|!=|=)\s*\d+(\.\d+)*)*$/, // primitive / cocoapods ~>
  /^[[\]]\d+(\.\d+)*,\d+(\.\d+)*[[\]]$/, // ivy bounded: [1.0,2.0]
  /^[[\]]\d+(\.\d+)*,\)$/, // ivy open upper: [1.0,)
  /^\(,\d+(\.\d+)*[[\]]$/, // ivy open lower: (,2.0]
];

export const validateTerraformVersion =
  (availableVersions?: string[]) =>
  (_: unknown, value: string | undefined): Promise<void> => {
    if (!value) return Promise.resolve();
    const trimmed = value.trim();
    if (!VERSION_CONSTRAINT_PATTERNS.some((p) => p.test(trimmed))) {
      return Promise.reject(new Error("Invalid version format"));
    }
    const isExactVersion = /^\d+(\.\d+)*$/.test(trimmed);
    if (isExactVersion && availableVersions && availableVersions.length > 0) {
      if (!availableVersions.includes(trimmed)) {
        return Promise.reject(new Error(`Version ${trimmed} is not available. Select a version from the list.`));
      }
    }
    return Promise.resolve();
  };
