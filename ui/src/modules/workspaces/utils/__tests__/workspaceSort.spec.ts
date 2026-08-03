import { JobStatus } from "../../../../domain/types";
import { WorkspaceListItem } from "../../types";
import {
  compareByName,
  compareByLastRun,
  compareByStatus,
  compareBySource,
  compareByTerraformVersion,
  sortWorkspaces,
} from "../workspaceSort";

const ws = (overrides: Partial<WorkspaceListItem>): WorkspaceListItem => ({
  id: overrides.id ?? "id",
  name: overrides.name ?? "name",
  iacType: "terraform",
  source: overrides.source ?? "",
  ...overrides,
});

describe("workspaceSort comparators", () => {
  it("compareByName orders alphabetically, case-insensitive", () => {
    const a = ws({ name: "beta" });
    const b = ws({ name: "Alpha" });
    expect(compareByName(a, b)).toBeGreaterThan(0);
    expect(compareByName(b, a)).toBeLessThan(0);
  });

  it("compareByLastRun orders oldest-first by raw timestamp", () => {
    const older = ws({ lastRun: "2024-01-01T00:00:00.000Z" });
    const newer = ws({ lastRun: "2024-06-01T00:00:00.000Z" });
    expect(compareByLastRun(older, newer)).toBeLessThan(0);
    expect(compareByLastRun(newer, older)).toBeGreaterThan(0);
  });

  it("compareByStatus orders by STATUS_ORDER rank", () => {
    const running = ws({ lastStatus: JobStatus.Running });
    const completed = ws({ lastStatus: JobStatus.Completed });
    expect(compareByStatus(running, completed)).toBeLessThan(0);
  });

  it("compareBySource orders alphabetically by source/normalizedSource", () => {
    const a = ws({ source: "github.com/org/b" });
    const b = ws({ source: "github.com/org/a" });
    expect(compareBySource(a, b)).toBeGreaterThan(0);
  });

  it("compareByTerraformVersion orders alphabetically", () => {
    const a = ws({ terraformVersion: "1.9.2" });
    const b = ws({ terraformVersion: "1.2.0" });
    expect(compareByTerraformVersion(a, b)).toBeGreaterThan(0);
  });

  it("sortWorkspaces('name_asc') still orders by name ascending", () => {
    const list = [ws({ id: "1", name: "beta" }), ws({ id: "2", name: "alpha" })];
    const sorted = sortWorkspaces(list, "name_asc");
    expect(sorted.map((w) => w.id)).toEqual(["2", "1"]);
  });

  it("sortWorkspaces('lastRun_desc') still orders newest-first", () => {
    const list = [
      ws({ id: "1", lastRun: "2024-01-01T00:00:00.000Z" }),
      ws({ id: "2", lastRun: "2024-06-01T00:00:00.000Z" }),
    ];
    const sorted = sortWorkspaces(list, "lastRun_desc");
    expect(sorted.map((w) => w.id)).toEqual(["2", "1"]);
  });
});
