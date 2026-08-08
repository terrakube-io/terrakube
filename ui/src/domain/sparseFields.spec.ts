import { Organization, sparseFields, Workspace } from "./types";

/**
 * The fragments used to be literals in the source, so a bundle grep confirmed what the client sent.
 * They are assembled at runtime now, so the wire format is pinned here instead.
 */
describe("sparseFields", () => {
  it("emits the fragment a hand written query string would have", () => {
    expect(sparseFields<Organization>("organization")("name")).toBe("fields[organization]=name");

    expect(sparseFields<Organization>("organization")("name", "description", "executionMode", "icon")).toBe(
      "fields[organization]=name,description,executionMode,icon"
    );

    expect(sparseFields<Workspace>("workspace")("source", "branch")).toBe("fields[workspace]=source,branch");
  });

  it("preserves the order the fields were declared in", () => {
    expect(sparseFields<Workspace>("workspace")("branch", "source")).toBe("fields[workspace]=branch,source");
  });

  it("interpolates into a url as a plain string", () => {
    const fields = sparseFields<Organization>("organization")("name");

    expect(`organization/abc?include=module&${fields}`).toBe(
      "organization/abc?include=module&fields[organization]=name"
    );
  });
});
