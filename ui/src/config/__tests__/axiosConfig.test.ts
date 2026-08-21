import { mgr } from "../authConfig";

jest.mock("../authConfig", () => ({
  mgr: { removeUser: jest.fn() },
}));

jest.mock("../authUser", () => ({
  __esModule: true,
  default: jest.fn(() => ({ access_token: "token" })),
}));

const mockRemoveUser = mgr.removeUser as jest.Mock;

describe("axiosConfig response interceptor", () => {
  beforeEach(() => {
    mockRemoveUser.mockClear();
  });

  it("signs the user out when a request comes back 401", async () => {
    // Importing after mocks are registered so the interceptors below are wired
    // against the mocked authConfig/authUser modules.
    const { default: axiosInstance } = await import("../axiosConfig");

    axiosInstance.defaults.adapter = () =>
      Promise.reject({
        isAxiosError: true,
        response: { status: 401, data: {}, statusText: "Unauthorized", headers: {}, config: {} },
        config: {},
        toJSON: () => ({}),
      });

    await expect(axiosInstance.get("/organizations")).rejects.toBeTruthy();
    expect(mockRemoveUser).toHaveBeenCalledTimes(1);
  });

  it("does not sign the user out on other error statuses (e.g. 403)", async () => {
    const { default: axiosInstance } = await import("../axiosConfig");

    axiosInstance.defaults.adapter = () =>
      Promise.reject({
        isAxiosError: true,
        response: { status: 403, data: {}, statusText: "Forbidden", headers: {}, config: {} },
        config: {},
        toJSON: () => ({}),
      });

    await expect(axiosInstance.get("/organizations")).rejects.toBeTruthy();
    expect(mockRemoveUser).not.toHaveBeenCalled();
  });
});
