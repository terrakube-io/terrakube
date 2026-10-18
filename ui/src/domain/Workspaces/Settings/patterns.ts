// The API stores a trigger's branches and paths as one comma-separated string and splits it on every comma, so
// the editor works on a list: one chip per pattern, and typing or pasting a comma starts the next one.
export const splitList = (value?: string) =>
  (value ?? "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);

export const joinList = (items?: string[]) => (items ?? []).join(",");

export const isValidRegex = (pattern: string) => {
  try {
    new RegExp(pattern);
    return true;
  } catch {
    return false;
  }
};

// The API matches a regex against the whole branch name or file path, so `main` does not match `main-2`.
export const matchesWhole = (pattern: string, value: string) => {
  try {
    return new RegExp(`^(?:${pattern})$`).test(value);
  } catch {
    return false;
  }
};

// `terraform/*` is a valid regex, but it means "terraform" and then any number of slashes: nearly always a wildcard
// typed into a regex field. It is valid, so it only warns.
export const looksLikeWildcard = (pattern: string) => /\/\*{1,2}$/.test(pattern);
