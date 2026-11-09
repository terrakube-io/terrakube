export type ProjectFacts = {
  teams: string[];
  /** Policy sets attached to the project itself. Global sets apply to every workspace either way. */
  policySets: { id: string; name: string; enforcementLevel?: string }[];
  collections: { id: string; name: string }[];
  defaultTags: { tagId: string; value?: string }[];
};

export type MoveImpactResult = {
  teamsGaining: string[];
  teamsLosing: string[];
  policiesStarting: ProjectFacts["policySets"];
  policiesStopping: ProjectFacts["policySets"];
  collectionsStarting: ProjectFacts["collections"];
  collectionsStopping: ProjectFacts["collections"];
  tagsInherited: ProjectFacts["defaultTags"];
};

export const NO_FACTS: ProjectFacts = { teams: [], policySets: [], collections: [], defaultTags: [] };

const difference = <T>(from: T[], without: T[], key: (item: T) => string): T[] => {
  const skip = new Set(without.map(key));
  return from.filter((item) => !skip.has(key(item)));
};

// What changes for the moved workspaces. A source of null means the workspaces were in no project.
export function computeMoveImpact(source: ProjectFacts | null, target: ProjectFacts): MoveImpactResult {
  const from = source ?? NO_FACTS;
  const id = (item: { id: string }) => item.id;
  const text = (value: string) => value;
  return {
    teamsGaining: difference(target.teams, from.teams, text),
    teamsLosing: difference(from.teams, target.teams, text),
    policiesStarting: difference(target.policySets, from.policySets, id),
    policiesStopping: difference(from.policySets, target.policySets, id),
    collectionsStarting: difference(target.collections, from.collections, id),
    collectionsStopping: difference(from.collections, target.collections, id),
    tagsInherited: target.defaultTags,
  };
}

export function hasAnyImpact(impact: MoveImpactResult): boolean {
  return (
    impact.teamsGaining.length +
      impact.teamsLosing.length +
      impact.policiesStarting.length +
      impact.policiesStopping.length +
      impact.collectionsStarting.length +
      impact.collectionsStopping.length +
      impact.tagsInherited.length >
    0
  );
}

const ENFORCEMENT: Record<string, string> = {
  HARD_MANDATORY: "Hard mandatory",
  SOFT_MANDATORY: "Soft mandatory",
  ADVISORY: "Advisory",
};

export function enforcementLabel(level?: string): string {
  return (level && ENFORCEMENT[level]) || "";
}
