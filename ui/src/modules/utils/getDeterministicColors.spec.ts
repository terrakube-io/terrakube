import getDeterministicColors from "./getDeterministicColors";
import isLightColor from "./isLightColor";

describe("getDeterministicColors", () => {
  it("picks dark text on light backgrounds and white text on dark ones", () => {
    for (const seed of ["production", "aws", "team:payments", "pci", "b1946ac9-2d3c-4e5f-8a7b-0c1d2e3f4a5b"]) {
      const { color, background } = getDeterministicColors(seed);
      expect(color).toBe(isLightColor(background) ? "#000000" : "#ffffff");
    }
  });
});
