import axiosInstance from "@/config/axiosConfig";
import { clearManagedInCode, loadManagedInCode, safeRepository } from "../managedInCode";

jest.mock("@/config/axiosConfig", () => ({ __esModule: true, default: { get: jest.fn() } }));
const get = axiosInstance.get as jest.Mock;

const answer = (attributes: object) => ({ data: { data: { attributes } } });

describe("loadManagedInCode", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    clearManagedInCode();
  });

  it("is null for an organization that is not kept in code", async () => {
    get.mockResolvedValue(answer({}));

    expect(await loadManagedInCode("org-1")).toBeNull();
  });

  it("gives the repository, and whether the screens are read-only", async () => {
    get.mockResolvedValue(
      answer({ managedInCodeRepository: "https://github.com/example/tf-admin", managedInCodeMode: "readonly" })
    );

    expect(await loadManagedInCode("org-1")).toEqual({
      repository: "https://github.com/example/tf-admin",
      readOnly: true,
    });
    expect(get).toHaveBeenCalledWith("organization/org-1", {
      params: { "fields[organization]": "managedInCodeRepository,managedInCodeMode" },
    });
  });

  it("only warns when no mode is set", async () => {
    get.mockResolvedValue(answer({ managedInCodeRepository: "https://github.com/example/x" }));

    expect(await loadManagedInCode("org-1")).toMatchObject({ readOnly: false });
  });

  it("counts a failed read as not managed, so a notice never blocks anyone", async () => {
    get.mockRejectedValue(new Error("down"));

    expect(await loadManagedInCode("org-1")).toBeNull();
  });

  it("reads each organization once a minute, and again after it is cleared", async () => {
    get.mockResolvedValue(answer({ managedInCodeRepository: "https://github.com/example/x" }));

    await loadManagedInCode("org-1");
    await loadManagedInCode("org-1");
    expect(get).toHaveBeenCalledTimes(1);

    clearManagedInCode("org-1");
    await loadManagedInCode("org-1");
    expect(get).toHaveBeenCalledTimes(2);
  });
});

describe("safeRepository", () => {
  it("keeps http and https addresses and drops anything else, since it is shown as a link", () => {
    expect(safeRepository("https://github.com/example/x")).toBe("https://github.com/example/x");
    expect(safeRepository("javascript:alert(1)")).toBeUndefined();
    expect(safeRepository("git@github.com:example/x.git")).toBeUndefined();
    expect(safeRepository("not a url")).toBeUndefined();
    expect(safeRepository(undefined)).toBeUndefined();
  });
});
