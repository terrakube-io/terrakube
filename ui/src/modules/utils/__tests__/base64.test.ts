import { decodeBase64, encodeBase64 } from "../base64";

describe("base64", () => {
  it.each(["hello", "Привет", "héllo 🚀", ""])("round-trips %j", (text) => {
    expect(decodeBase64(encodeBase64(text))).toBe(text);
  });

  it("matches what the API and the old Buffer-based code produced", () => {
    expect(encodeBase64("héllo")).toBe("aMOpbGxv");
    expect(decodeBase64("aMOpbGxv")).toBe("héllo");
    expect(decodeBase64("Y29uc3QgeCA9IDE7")).toBe("const x = 1;");
  });
});
