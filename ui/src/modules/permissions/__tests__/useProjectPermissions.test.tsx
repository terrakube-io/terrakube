import { renderHook, waitFor } from "@testing-library/react";
import axiosInstance from "../../../config/axiosConfig";
import { useProjectPermissions } from "../useProjectPermissions";

jest.mock("../../../config/axiosConfig", () => ({ __esModule: true, default: { get: jest.fn() } }));

const get = axiosInstance.get as jest.Mock;

describe("useProjectPermissions", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (window as any)._env_ = { REACT_APP_TERRAKUBE_API_URL: "https://api.example.com/api/v1" };
  });

  it("loads the effective permissions for the project", async () => {
    get.mockResolvedValue({ data: { manageProject: true, planJob: true } });

    const { result } = renderHook(() => useProjectPermissions("org-1", "project-1"));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(get).toHaveBeenCalledWith(
      "https://api.example.com/access-token/v1/teams/permissions/organization/org-1/project/project-1"
    );
    expect(result.current.permissions).toMatchObject({ manageProject: true, planJob: true, manageWorkspace: false });
  });

  it("stays read-only when the request fails", async () => {
    get.mockRejectedValue(new Error("404"));

    const { result } = renderHook(() => useProjectPermissions("org-1", "project-1"));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.permissions.manageProject).toBe(false);
  });

  it("does not call the API without a project", async () => {
    const { result } = renderHook(() => useProjectPermissions("org-1", undefined));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(get).not.toHaveBeenCalled();
  });
});
