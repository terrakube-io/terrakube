import { stripAnsi } from "../stripAnsi";

describe("stripAnsi", () => {
  it("strips SGR color codes", () => {
    expect(stripAnsi("\u001b[31mred text\u001b[0m")).toBe("red text");
  });

  it("strips multiple ANSI sequences", () => {
    expect(stripAnsi("\u001b[1m\u001b[32mbold green\u001b[0m normal")).toBe("bold green normal");
  });

  it("returns plain text unchanged", () => {
    expect(stripAnsi("hello world")).toBe("hello world");
  });

  it("handles empty string", () => {
    expect(stripAnsi("")).toBe("");
  });

  it("strips cursor movement codes", () => {
    expect(stripAnsi("\u001b[2Jcleared")).toBe("cleared");
  });
});
