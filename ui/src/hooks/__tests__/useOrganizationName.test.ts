import { act, renderHook, waitFor } from "@testing-library/react";
import axiosInstance from "@/config/axiosConfig";
import { ORGANIZATION_NAME } from "@/config/actionTypes";
import { cacheOrganizationName, useOrganizationName } from "../useOrganizationName";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn() },
}));

const get = axiosInstance.get as jest.Mock;
const ORG_A = "aaaaaaaa-0000-0000-0000-000000000001";
const ORG_B = "bbbbbbbb-0000-0000-0000-000000000002";
const ORG_C = "cccccccc-0000-0000-0000-000000000003";
const ORG_D = "dddddddd-0000-0000-0000-000000000004";

const respond = (names: Record<string, string>) =>
  get.mockImplementation((url: string) => {
    const id = Object.keys(names).find((key) => url.startsWith(`organization/${key}?`));
    return id
      ? Promise.resolve({ data: { data: { id, attributes: { name: names[id] } } } })
      : Promise.reject(new Error("not found"));
  });

describe("useOrganizationName", () => {
  beforeEach(() => {
    get.mockReset();
    sessionStorage.clear();
  });

  it("resolves the name of the organization in the URL, not the last visited one", async () => {
    sessionStorage.setItem(ORGANIZATION_NAME, "simple");
    respond({ [ORG_A]: "simple-governance" });

    const { result } = renderHook(() => useOrganizationName(ORG_A));

    expect(result.current).toBeUndefined();
    await waitFor(() => expect(result.current).toBe("simple-governance"));
    expect(get).toHaveBeenCalledWith(`organization/${ORG_A}?fields[organization]=name`);
    expect(sessionStorage.getItem(`${ORGANIZATION_NAME}:${ORG_A}`)).toBe("simple-governance");
  });

  it("switches names with the id and fetches each organization once", async () => {
    respond({ [ORG_B]: "beta", [ORG_C]: "gamma" });

    const { result, rerender } = renderHook(({ id }) => useOrganizationName(id), { initialProps: { id: ORG_B } });
    await waitFor(() => expect(result.current).toBe("beta"));

    rerender({ id: ORG_C });
    expect(result.current).toBeUndefined();
    await waitFor(() => expect(result.current).toBe("gamma"));

    rerender({ id: ORG_B });
    expect(result.current).toBe("beta");
    expect(get).toHaveBeenCalledTimes(2);
  });

  it("uses the per-id session cache and picks up renames", () => {
    sessionStorage.setItem(`${ORGANIZATION_NAME}:${ORG_D}`, "delta");

    const { result } = renderHook(() => useOrganizationName(ORG_D));
    expect(result.current).toBe("delta");
    expect(get).not.toHaveBeenCalled();

    act(() => cacheOrganizationName(ORG_D, "delta-renamed"));
    expect(result.current).toBe("delta-renamed");
  });

  it("does nothing without a valid organization id", () => {
    const { result } = renderHook(() => useOrganizationName("create"));
    expect(result.current).toBeUndefined();
    expect(get).not.toHaveBeenCalled();
  });
});
