import { reportDeprecatedScope } from "../deprecations";

describe("reportDeprecatedScope", () => {
  let error: jest.SpyInstance;
  beforeEach(() => {
    error = jest.spyOn(console, "error").mockImplementation(() => {});
  });
  afterEach(() => error.mockRestore());

  it("reports DateTime once per action", () => {
    const source = "({ context }) => <p>{DateTime.now().toISO()}</p>";
    expect(reportDeprecatedScope(source, "action-a")).toEqual(["DateTime"]);
    reportDeprecatedScope(source, "action-a");
    expect(error).toHaveBeenCalledTimes(1);
    expect(error.mock.calls[0][0]).toMatch(/DateTime.*deprecated.*dayjs/);
  });

  it("reports again for a different action", () => {
    reportDeprecatedScope("() => DateTime.local()", "action-b");
    expect(error).toHaveBeenCalledTimes(1);
  });

  it("ignores actions that do not use it", () => {
    expect(reportDeprecatedScope("() => <p>{dayjs().format('YYYY')}</p>", "action-c")).toEqual([]);
    expect(reportDeprecatedScope("() => <p>{myDateTimeHelper()}</p>", "action-d")).toEqual([]);
    expect(error).not.toHaveBeenCalled();
  });
});
