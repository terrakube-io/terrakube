import { SECTION_LABELS, projectPath, sectionFromParam } from "../projectNav";

describe("project sections", () => {
  it("knows the Variables section and its address", () => {
    expect(sectionFromParam("variables")).toBe("variables");
    expect(projectPath("org", "p1", "variables")).toBe("/organizations/org/projects/p1/variables");
    expect(SECTION_LABELS.variables).toBe("Variables");
  });

  it("falls back to the overview for an unknown section", () => {
    expect(sectionFromParam("nope")).toBe("overview");
    expect(sectionFromParam(undefined)).toBe("overview");
  });
});
