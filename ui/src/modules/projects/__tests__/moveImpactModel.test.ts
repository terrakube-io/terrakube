import { NO_FACTS, ProjectFacts, computeMoveImpact, enforcementLabel, hasAnyImpact } from "../moveImpactModel";

const facts = (overrides: Partial<ProjectFacts> = {}): ProjectFacts => ({ ...NO_FACTS, ...overrides });

describe("computeMoveImpact", () => {
  it("lists the teams that gain and the teams that lose access", () => {
    const impact = computeMoveImpact(
      facts({ teams: ["platform", "shared"] }),
      facts({ teams: ["payments-devs", "shared", "payments-admins"] })
    );

    expect(impact.teamsGaining).toEqual(["payments-devs", "payments-admins"]);
    expect(impact.teamsLosing).toEqual(["platform"]);
  });

  it("starts and stops policy sets and collections that are attached to only one of the projects", () => {
    const guardrails = { id: "p1", name: "pwd-guardrails", enforcementLevel: "SOFT_MANDATORY" };
    const shared = { id: "p2", name: "baseline" };
    const common = { id: "c1", name: "payments-common" };
    const old = { id: "c2", name: "platform-old" };

    const impact = computeMoveImpact(
      facts({ policySets: [shared], collections: [old] }),
      facts({ policySets: [guardrails, shared], collections: [common] })
    );

    expect(impact.policiesStarting).toEqual([guardrails]);
    expect(impact.policiesStopping).toEqual([]);
    expect(impact.collectionsStarting).toEqual([common]);
    expect(impact.collectionsStopping).toEqual([old]);
  });

  it("treats workspaces that were in no project as having no access, policy or variables to lose", () => {
    const impact = computeMoveImpact(null, facts({ teams: ["a"], collections: [{ id: "c", name: "x" }] }));

    expect(impact.teamsGaining).toEqual(["a"]);
    expect(impact.teamsLosing).toEqual([]);
    expect(impact.collectionsStarting).toHaveLength(1);
  });

  it("reports the destination's default tags as inherited", () => {
    const impact = computeMoveImpact(facts(), facts({ defaultTags: [{ tagId: "t1", value: "prod" }] }));

    expect(impact.tagsInherited).toEqual([{ tagId: "t1", value: "prod" }]);
  });

  it("has no impact between two identical projects", () => {
    const same = facts({ teams: ["a"] });

    expect(hasAnyImpact(computeMoveImpact(same, same))).toBe(false);
  });
});

describe("enforcementLabel", () => {
  it("uses the wording of the design", () => {
    expect(enforcementLabel("SOFT_MANDATORY")).toBe("Soft mandatory");
    expect(enforcementLabel(undefined)).toBe("");
  });
});
