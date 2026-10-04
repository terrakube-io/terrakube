import { diffState, SENSITIVE_MASK } from "../stateDiff";

describe("diffState", () => {
  it("returns undefined for equal documents", () => {
    expect(diffState({ a: 1, b: [1, { c: null }] }, { a: 1, b: [1, { c: null }] })).toBeUndefined();
  });

  it("keeps only added, removed and changed keys", () => {
    expect(diffState({ lineage: "x", serial: 1, gone: true }, { lineage: "x", serial: 2, added: "y" })).toEqual({
      kind: "object",
      children: [
        ["serial", { kind: "changed", prev: 1, next: 2 }],
        ["gone", { kind: "removed", prev: true }],
        ["added", { kind: "added", next: "y" }],
      ],
    });
  });

  it("recurses into nested objects", () => {
    expect(diffState({ a: { b: { c: 1, d: 2 } } }, { a: { b: { c: 1, d: 3 } } })).toEqual({
      kind: "object",
      children: [
        [
          "a",
          {
            kind: "object",
            children: [["b", { kind: "object", children: [["d", { kind: "changed", prev: 2, next: 3 }]] }]],
          },
        ],
      ],
    });
  });

  it("compares arrays by index, including added and removed items", () => {
    expect(diffState([1, 2, 3], [1, 5])).toEqual({
      kind: "array",
      children: [
        [1, { kind: "changed", prev: 2, next: 5 }],
        [2, { kind: "removed", prev: 3 }],
      ],
    });
    expect(diffState([], [{ id: 1 }])).toEqual({ kind: "array", children: [[0, { kind: "added", next: { id: 1 } }]] });
  });

  it("treats a type change as a changed value", () => {
    expect(diffState({ a: [1] }, { a: { 0: 1 } })).toEqual({
      kind: "object",
      children: [["a", { kind: "changed", prev: [1], next: { 0: 1 } }]],
    });
    expect(diffState(null, {})).toEqual({ kind: "changed", prev: null, next: {} });
  });

  it("does not treat inherited properties as present", () => {
    expect(diffState({}, { constructor: 1 })).toEqual({
      kind: "object",
      children: [["constructor", { kind: "added", next: 1 }]],
    });
  });

  it("masks sensitive output values on both sides but still reports the change", () => {
    const prev = { outputs: { token: { value: "old-secret", type: "string", sensitive: true }, url: { value: "a" } } };
    const next = { outputs: { token: { value: "new-secret", type: "string", sensitive: true }, url: { value: "b" } } };
    expect(diffState(prev, next)).toEqual({
      kind: "object",
      children: [
        [
          "outputs",
          {
            kind: "object",
            children: [
              [
                "token",
                {
                  kind: "object",
                  children: [["value", { kind: "changed", prev: SENSITIVE_MASK, next: SENSITIVE_MASK }]],
                },
              ],
              ["url", { kind: "object", children: [["value", { kind: "changed", prev: "a", next: "b" }]] }],
            ],
          },
        ],
      ],
    });
  });

  it("masks sensitive values inside added and removed subtrees", () => {
    const secret = { db: { value: { password: "hunter2" }, type: ["object"], sensitive: true } };
    expect(JSON.stringify(diffState({ outputs: {} }, { outputs: secret }))).not.toContain("hunter2");
    expect(JSON.stringify(diffState({ outputs: secret }, { outputs: {} }))).not.toContain("hunter2");
    expect(JSON.stringify(diffState({ outputs: secret }, null))).not.toContain("hunter2");
  });

  describe("sensitive resource attributes", () => {
    const state = (attributes: Record<string, unknown>, sensitive: unknown[] = []) => ({
      resources: [{ type: "random_password", instances: [{ attributes, sensitive_attributes: sensitive }] }],
    });
    const result = [[{ type: "get_attr", value: "result" }]];

    it("masks a changed sensitive attribute on both sides and still reports the change", () => {
      const diff = diffState(
        state({ result: "old-pw", length: 8 }, result),
        state({ result: "new-pw", length: 9 }, result)
      );
      const text = JSON.stringify(diff);
      expect(text).not.toContain("old-pw");
      expect(text).not.toContain("new-pw");
      expect(text).toContain(
        JSON.stringify(["result", { kind: "changed", prev: SENSITIVE_MASK, next: SENSITIVE_MASK }])
      );
      expect(text).toContain(JSON.stringify(["length", { kind: "changed", prev: 8, next: 9 }]));
    });

    it("reports nothing for an unchanged sensitive attribute", () => {
      expect(diffState(state({ result: "pw" }, result), state({ result: "pw" }, result))).toBeUndefined();
    });

    it("masks when only one side lists the attribute as sensitive, and in added or removed instances", () => {
      expect(JSON.stringify(diffState(state({ result: "old-pw" }), state({ result: "new-pw" }, result)))).not.toMatch(
        /-pw/
      );
      expect(JSON.stringify(diffState({ resources: [] }, state({ result: "new-pw" }, result)))).not.toContain("new-pw");
      expect(JSON.stringify(diffState(state({ result: "old-pw" }, result), { resources: [] }))).not.toContain("old-pw");
    });

    it("follows get_attr and index steps into nested values", () => {
      const path = [
        [
          { type: "get_attr", value: "users" },
          { type: "index", value: { value: 1, type: "number" } },
          { type: "get_attr", value: "password" },
        ],
        [
          { type: "get_attr", value: "tokens" },
          { type: "index", value: { value: "ci", type: "string" } },
        ],
      ];
      const prev = state({ users: [{ password: "a1" }, { password: "b1", name: "x" }], tokens: { ci: "t1" } }, path);
      const next = state({ users: [{ password: "a2" }, { password: "b2", name: "y" }], tokens: { ci: "t2" } }, path);
      const text = JSON.stringify(diffState(prev, next));
      expect(text).toContain('"a1"');
      expect(text).not.toMatch(/"b[12]"|"t[12]"/);
      expect(text).toContain('"y"');
    });
  });
});
