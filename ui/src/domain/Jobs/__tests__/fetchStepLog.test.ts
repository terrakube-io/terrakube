import { fetchStepLog, StepLogFetchError, StepLogNotFoundError } from "../fetchStepLog";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), head: jest.fn() },
  axiosClient: { get: jest.fn(), head: jest.fn() },
  axiosAuxiliary: { get: jest.fn(), head: jest.fn() },
}));

jest.mock("../outputUrl", () => ({
  getJobOutputRequestUrl: (o: string) => o,
  getPublicApiOrigin: () => "https://api.example.com",
  isTerrakubeApiUrl: () => true,
}));

import { axiosAuxiliary } from "../../../config/axiosConfig";

const ac = () => new AbortController().signal;
const base = {
  output: "https://api.example.com/tfoutput/v1/organization/o/job/j/step/s",
  jobId: "j",
  organizationId: "o",
  stepId: "s",
};

describe("fetchStepLog", () => {
  beforeEach(() => jest.clearAllMocks());

  it("returns the body for a small completed-step log", async () => {
    (axiosAuxiliary.head as jest.Mock).mockResolvedValue({ headers: { "content-length": "12" } });
    (axiosAuxiliary.get as jest.Mock).mockResolvedValue({ status: 200, data: "hello world!", headers: {} });

    const result = await fetchStepLog({ ...base, signal: ac() });

    expect(result).toEqual({ text: "hello world!", truncated: false });
  });

  it("tails a large log with a Range request and marks it truncated", async () => {
    (axiosAuxiliary.head as jest.Mock).mockResolvedValue({
      headers: { "content-length": String(5 * 1024 * 1024) },
    });
    (axiosAuxiliary.get as jest.Mock).mockResolvedValue({
      status: 206,
      data: "...tail...",
      headers: { "content-range": "bytes 5000000-5242879/5242880" },
    });

    const result = await fetchStepLog({ ...base, signal: ac(), tailBytes: 262144 });

    expect(result.truncated).toBe(true);
    expect((axiosAuxiliary.get as jest.Mock).mock.calls[0][1].headers.Range).toBe("bytes=-262144");
  });

  it("does not tail when the object is under the threshold", async () => {
    (axiosAuxiliary.head as jest.Mock).mockResolvedValue({ headers: { "content-length": "1024" } });
    (axiosAuxiliary.get as jest.Mock).mockResolvedValue({ status: 200, data: "small", headers: {} });

    await fetchStepLog({ ...base, signal: ac(), tailBytes: 262144 });

    expect((axiosAuxiliary.get as jest.Mock).mock.calls[0][1].headers).toBeUndefined();
  });

  it("throws StepLogNotFoundError on 404", async () => {
    (axiosAuxiliary.head as jest.Mock).mockRejectedValue({ response: { status: 404 } });

    await expect(fetchStepLog({ ...base, signal: ac() })).rejects.toBeInstanceOf(StepLogNotFoundError);
  });

  it("throws StepLogFetchError with status on 502", async () => {
    (axiosAuxiliary.head as jest.Mock).mockResolvedValue({ headers: {} });
    (axiosAuxiliary.get as jest.Mock).mockRejectedValue({ response: { status: 502 } });

    await expect(fetchStepLog({ ...base, signal: ac() })).rejects.toMatchObject({
      name: "StepLogFetchError",
      status: 502,
    });
  });

  it("falls through to GET when HEAD is rejected with a non-404", async () => {
    (axiosAuxiliary.head as jest.Mock).mockRejectedValue({ response: { status: 405 } });
    (axiosAuxiliary.get as jest.Mock).mockResolvedValue({ status: 200, data: "body", headers: {} });

    const result = await fetchStepLog({ ...base, signal: ac() });

    expect(result.text).toBe("body");
  });
});
