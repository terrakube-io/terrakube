import { sortWorkspaceTagBindings } from "./workspaceTags";

const tags = [
  { id: "tag-1", name: "env" },
  { id: "tag-2", name: "team" },
  { id: "tag-3", name: "owner" },
];

describe("sortWorkspaceTagBindings", () => {
  it("orders by key name, not by the order the API returned", () => {
    const sorted = sortWorkspaceTagBindings(
      [
        { tagId: "tag-2", value: "infra" },
        { tagId: "tag-3", value: "alice" },
        { tagId: "tag-1", value: "prod" },
      ],
      tags
    );
    expect(sorted.map((b) => b.tagId)).toEqual(["tag-1", "tag-3", "tag-2"]);
  });

  it("orders by value when two bindings share a key", () => {
    const sorted = sortWorkspaceTagBindings(
      [
        { tagId: "tag-1", value: "prod" },
        { tagId: "tag-1", value: "dev" },
      ],
      tags
    );
    expect(sorted.map((b) => b.value)).toEqual(["dev", "prod"]);
  });

  it("puts a tag with no value before the same key with one", () => {
    const sorted = sortWorkspaceTagBindings(
      [
        { tagId: "tag-1", value: "dev" },
        { tagId: "tag-1", value: null },
      ],
      tags
    );
    expect(sorted.map((b) => b.value)).toEqual([null, "dev"]);
  });

  it("falls back to the tag id while the organization tag list is still loading", () => {
    const sorted = sortWorkspaceTagBindings([{ tagId: "b" }, { tagId: "a" }], []);
    expect(sorted.map((b) => b.tagId)).toEqual(["a", "b"]);
  });

  it("leaves the caller's array alone", () => {
    const bindings = [{ tagId: "tag-2" }, { tagId: "tag-1" }];
    sortWorkspaceTagBindings(bindings, tags);
    expect(bindings.map((b) => b.tagId)).toEqual(["tag-2", "tag-1"]);
  });
});
