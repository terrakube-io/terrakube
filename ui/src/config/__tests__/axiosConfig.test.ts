import { mgr } from "../authConfig";
import { getBackendError, setBackendError } from "@/modules/api/backendStatus";
import {
  getAuxiliaryRequestFailureCounts,
  getGlobalBackendErrorActivations,
  resetRequestMetrics,
} from "@/modules/api/requestMetrics";

jest.mock("../authConfig", () => ({
  mgr: { removeUser: jest.fn() },
}));

jest.mock("../authUser", () => ({
  __esModule: true,
  default: jest.fn(() => ({ access_token: "token" })),
}));

const mockRemoveUser = mgr.removeUser as jest.Mock;

const rejectWith =
  (status: number | undefined, extra: Record<string, unknown> = {}) =>
  (config: unknown) =>
    Promise.reject({
      isAxiosError: true,
      config,
      response:
        status == null
          ? undefined
          : { status, data: {}, statusText: "", headers: {}, config },
      toJSON: () => ({}),
      ...extra,
    });

describe("axiosConfig response interceptor", () => {
  beforeEach(() => {
    mockRemoveUser.mockClear();
    setBackendError(null);
    resetRequestMetrics();
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

  it("sets global backend-error state on a core 503 and records the activation", async () => {
    const { default: axiosInstance } = await import("../axiosConfig");
    axiosInstance.defaults.adapter = rejectWith(503);

    await expect(axiosInstance.get("/organizations")).rejects.toBeTruthy();

    expect(getBackendError()).toBe(503);
    expect(getGlobalBackendErrorActivations()).toEqual({ core: 1 });
  });
});

describe("axiosAuxiliary client", () => {
  beforeEach(() => {
    mockRemoveUser.mockClear();
    setBackendError(null);
    resetRequestMetrics();
  });

  it.each([404, 429, 500, 502, 503, 504])(
    "keeps a %s failure local - no global backend-error activation",
    async (status) => {
      const { axiosAuxiliary } = await import("../axiosConfig");
      axiosAuxiliary.defaults.adapter = rejectWith(status);

      await expect(axiosAuxiliary.get("/context/v1/1", { auxClass: "context" })).rejects.toBeTruthy();

      expect(getBackendError()).toBeNull();
      expect(getGlobalBackendErrorActivations()).toEqual({});
      expect(getAuxiliaryRequestFailureCounts()).toEqual({ [`context:${status}`]: 1 });
    }
  );

  it("classifies a network error (no response) as network", async () => {
    const { axiosAuxiliary } = await import("../axiosConfig");
    axiosAuxiliary.defaults.adapter = rejectWith(undefined, { message: "Network Error" });

    await expect(axiosAuxiliary.get("/tfoutput/v1/x", { auxClass: "step-log" })).rejects.toBeTruthy();

    expect(getBackendError()).toBeNull();
    expect(getAuxiliaryRequestFailureCounts()).toEqual({ "step-log:network": 1 });
  });

  it("still signs the user out on a 401", async () => {
    const { axiosAuxiliary } = await import("../axiosConfig");
    axiosAuxiliary.defaults.adapter = rejectWith(401);

    await expect(axiosAuxiliary.get("/context/v1/1", { auxClass: "context" })).rejects.toBeTruthy();

    expect(mockRemoveUser).toHaveBeenCalledTimes(1);
    expect(getBackendError()).toBeNull();
  });

  it("still enriches a 403 with a permission message", async () => {
    const { axiosAuxiliary } = await import("../axiosConfig");
    axiosAuxiliary.defaults.adapter = rejectWith(403);

    await expect(axiosAuxiliary.get("/context/v1/1", { auxClass: "context" })).rejects.toMatchObject({
      permissionError: true,
    });
    expect(mockRemoveUser).not.toHaveBeenCalled();
  });

  it("a successful auxiliary response does not clear a genuine global outage", async () => {
    const { axiosAuxiliary } = await import("../axiosConfig");
    setBackendError(503);
    axiosAuxiliary.defaults.adapter = () =>
      Promise.resolve({
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config: {} as never,
      });

    await axiosAuxiliary.get("/context/v1/1", { auxClass: "context" });

    expect(getBackendError()).toBe(503);
  });
});
