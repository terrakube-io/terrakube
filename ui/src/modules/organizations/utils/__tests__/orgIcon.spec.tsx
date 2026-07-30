import { render } from "@testing-library/react";
import { parseIconField, getOrgIcon } from "../orgIcon";

describe("orgIcon", () => {
  describe("parseIconField", () => {
    it("returns FaBuilding and a deterministic color when icon field is empty", () => {
      const result = parseIconField(undefined, "org-1");
      expect(result.iconName).toBe("FaBuilding");
      expect(result.color).toMatch(/^#[0-9a-f]{6}$/i);
    });

    it("parses 'IconName:color' format", () => {
      const result = parseIconField("FaRocket:#123456", "org-1");
      expect(result).toEqual({ iconName: "FaRocket", color: "#123456" });
    });

    it("defaults color to black when only icon name is given", () => {
      const result = parseIconField("FaRocket", "org-1");
      expect(result).toEqual({ iconName: "FaRocket", color: "#000000" });
    });
  });

  describe("getOrgIcon", () => {
    it("renders without crashing for a known icon name", () => {
      const { container } = render(<div>{getOrgIcon("FaBuilding", "#ff0000")}</div>);
      expect(container.querySelector("svg")).toBeInTheDocument();
    });

    it("falls back to FaBuilding for an unknown icon name", () => {
      const { container } = render(<div>{getOrgIcon("NotARealIcon", "#ff0000")}</div>);
      expect(container.querySelector("svg")).toBeInTheDocument();
    });
  });
});
