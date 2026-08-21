import axios from "axios";
import { mgr } from "@/config/authConfig";
import { apiGet } from "../apiWrapper";

jest.mock("axios", () => {
  const mockAxios = jest.fn();
  (mockAxios as any).isAxiosError = jest.fn(() => true);
  return { __esModule: true, default: mockAxios };
});

jest.mock("@/config/authConfig", () => ({
  mgr: { removeUser: jest.fn() },
}));

jest.mock("@/config/authUser", () => ({
  __esModule: true,
  default: jest.fn(() => ({ access_token: "token" })),
}));

const mockedAxios = axios as unknown as jest.Mock;
const mockRemoveUser = mgr.removeUser as jest.Mock;

describe("apiWrapper 401 handling", () => {
  beforeEach(() => {
    mockedAxios.mockReset();
    mockRemoveUser.mockClear();
  });

  it("signs the user out and returns a friendly message when the API rejects the token with 401", async () => {
    mockedAxios.mockRejectedValue({
      response: { status: 401, data: {}, statusText: "Unauthorized" },
    });

    const result = await apiGet("/organizations");

    expect(mockRemoveUser).toHaveBeenCalledTimes(1);
    expect(result.isError).toBe(true);
    expect(result.responseCode).toBe(401);
  });

  it("does not sign the user out on unrelated errors (e.g. 403)", async () => {
    mockedAxios.mockRejectedValue({
      response: { status: 403, data: {}, statusText: "Forbidden" },
    });

    const result = await apiGet("/organizations");

    expect(mockRemoveUser).not.toHaveBeenCalled();
    expect(result.isError).toBe(true);
    expect(result.responseCode).toBe(403);
  });
});
