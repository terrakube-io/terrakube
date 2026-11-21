import { renderHook, waitFor } from "@testing-library/react";
import axiosInstance from "../../../config/axiosConfig";
import { useOrgPermissions } from "../useOrgPermissions";

jest.mock("../../../config/axiosConfig", () => ({ __esModule: true, default: { get: jest.fn() } }));
jest.mock("@/config/apiUrls", () => ({ apiUrl: (path: string) => `https://api.example.com${path}` }));
jest.mock("react-router-dom", () => ({ ...jest.requireActual("react-router-dom"), useParams: () => ({}) }));

const get = axiosInstance.get as jest.Mock;

describe("useOrgPermissions", () => {
  beforeEach(() => jest.clearAllMocks());

  it("reads the role and the teams it comes through, when the API says", async () => {
    get.mockResolvedValue({ data: { manageWorkspace: true, accessLevel: "write", grantedThrough: ["devs", "ops"] } });

    const { result } = renderHook(() => useOrgPermissions("org-1"));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.permissions).toMatchObject({
      manageWorkspace: true,
      accessLevel: "write",
      grantedThrough: ["devs", "ops"],
    });
  });

  it("leaves the role out for an API that does not send it, so the chip stays hidden", async () => {
    get.mockResolvedValue({ data: { manageWorkspace: true } });

    const { result } = renderHook(() => useOrgPermissions("org-1"));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.permissions.accessLevel).toBeUndefined();
    expect(result.current.permissions.grantedThrough).toEqual([]);
  });
});
