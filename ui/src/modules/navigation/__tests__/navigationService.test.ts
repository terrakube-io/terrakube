import { apiPost } from "@/modules/api/apiWrapper";
import { findWorkspaceByName, resolveOrganization } from "../navigationService";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));
const post = apiPost as jest.Mock;

const orgs = (...names: [string, string][]) => ({
  isError: false,
  data: { organization: { edges: names.map(([id, name]) => ({ node: { id, name } })) } },
});

describe("resolveOrganization", () => {
  it("finds an organization by exact name, by id, or by a unique name ignoring case", async () => {
    post.mockResolvedValue(
      orgs(["3fa85f64-5717-4562-b3fc-2c963f66afa6", "JXP"], ["4fa85f64-5717-4562-b3fc-2c963f66afa6", "Admin"])
    );

    expect(await resolveOrganization("JXP")).toMatchObject({ name: "JXP" });
    expect(await resolveOrganization("jxp")).toMatchObject({ name: "JXP" });
    expect(await resolveOrganization("3fa85f64-5717-4562-b3fc-2c963f66afa6")).toMatchObject({ name: "JXP" });
    expect(await resolveOrganization("missing")).toBeNull();
  });

  it("does not guess between organizations that differ only by case", async () => {
    post.mockResolvedValue(orgs(["a", "Data"], ["b", "data"]));

    expect(await resolveOrganization("DATA")).toBeNull();
    expect(await resolveOrganization("data")).toMatchObject({ id: "b" });
  });
});

describe("findWorkspaceByName", () => {
  it("quotes the name so punctuation stays part of it", async () => {
    post.mockResolvedValue({
      isError: false,
      data: { organization: { edges: [{ node: { workspace: { edges: [{ node: { id: "w", name: 'a"b' } }] } } }] } },
    });

    const found = await findWorkspaceByName("org", 'a"b');

    expect(found).toEqual({ id: "w", name: 'a"b' });
    expect(post.mock.calls[post.mock.calls.length - 1][1].variables.filter).toBe('name=="a\\"b"');
  });

  it("returns nothing for a name with no match, and fails loudly when the request fails", async () => {
    post.mockResolvedValue({
      isError: false,
      data: { organization: { edges: [{ node: { workspace: { edges: [] } } }] } },
    });
    expect(await findWorkspaceByName("org", "x")).toBeNull();

    post.mockResolvedValue({ isError: true, error: { message: "down" } });
    await expect(findWorkspaceByName("org", "x")).rejects.toThrow("down");
  });
});
