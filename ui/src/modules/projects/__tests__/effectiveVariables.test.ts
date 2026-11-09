import { CollectionEntry, VariableEntry, describeSource, resolveEffectiveVariables } from "../effectiveVariables";

const variable = (key: string, value: string, overrides: Partial<VariableEntry> = {}): VariableEntry => ({
  id: `${key}-${value}`,
  key,
  value,
  category: "ENV",
  sensitive: false,
  hcl: false,
  ...overrides,
});

const collection = (
  name: string,
  scope: CollectionEntry["scope"],
  priority: number,
  items: VariableEntry[]
): CollectionEntry => ({ id: name, name, scope, priority, items });

describe("resolveEffectiveVariables", () => {
  it("takes a key from the workspace first, then the organisation globals, then collections", () => {
    const rows = resolveEffectiveVariables(
      [variable("AWS_REGION", "eu-west-1")],
      [variable("AWS_REGION", "us-east-1"), variable("TF_VAR_env", "prod")],
      [collection("payments-common", "PROJECT", 1, [variable("LOG_LEVEL", "info"), variable("TF_VAR_env", "dev")])]
    );

    const byKey = Object.fromEntries(rows.map((row) => [row.key, row]));
    expect(byKey.AWS_REGION.value).toBe("eu-west-1");
    expect(byKey.AWS_REGION.source).toEqual({ type: "workspace" });
    expect(byKey.TF_VAR_env.value).toBe("prod");
    expect(byKey.TF_VAR_env.source).toEqual({ type: "global" });
    expect(byKey.LOG_LEVEL.source).toEqual({
      type: "collection",
      id: "payments-common",
      name: "payments-common",
      scope: "PROJECT",
    });
  });

  it("lets a narrower collection scope beat a wider one even when the wider has a higher priority", () => {
    const rows = resolveEffectiveVariables(
      [],
      [],
      [
        collection("org-wide", "ORGANIZATION", 99, [variable("K", "organisation")]),
        collection("for-project", "PROJECT", 1, [variable("K", "project")]),
        collection("for-workspace", "WORKSPACE", 1, [variable("K", "workspace")]),
      ]
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].value).toBe("workspace");
  });

  it("uses priority to choose between collections of the same scope", () => {
    const rows = resolveEffectiveVariables(
      [],
      [],
      [
        collection("low", "PROJECT", 1, [variable("K", "low")]),
        collection("high", "PROJECT", 10, [variable("K", "high")]),
      ]
    );

    expect(rows[0].value).toBe("high");
  });

  it("keeps Terraform variables and environment variables apart", () => {
    const rows = resolveEffectiveVariables(
      [variable("region", "a", { category: "TERRAFORM" })],
      [variable("region", "b", { category: "ENV" })],
      []
    );

    expect(rows).toHaveLength(2);
  });

  it("never exposes a value the API hid, and keeps the sensitive flag", () => {
    const rows = resolveEffectiveVariables([variable("DB_PASSWORD", "", { sensitive: true, value: null })], [], []);

    expect(rows[0].sensitive).toBe(true);
    expect(rows[0].value).toBeNull();
  });

  it("sorts by key", () => {
    const rows = resolveEffectiveVariables([variable("B", "1"), variable("A", "1")], [], []);

    expect(rows.map((row) => row.key)).toEqual(["A", "B"]);
  });
});

describe("describeSource", () => {
  it("names each kind of source as in the design", () => {
    expect(describeSource({ type: "workspace" })).toBe("workspace");
    expect(describeSource({ type: "global" })).toBe("organisation global");
    expect(describeSource({ type: "collection", id: "c", name: "payments-common", scope: "PROJECT" })).toBe(
      'collection "payments-common" (project)'
    );
  });
});
