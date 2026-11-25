import { Alert, Skeleton } from "antd";
import { useEffect, useState } from "react";
import projectService from "./projectService";
import {
  MoveImpactResult,
  NO_FACTS,
  ProjectFacts,
  computeMoveImpact,
  enforcementLabel,
  hasAnyImpact,
} from "./moveImpactModel";

type Props = {
  orgid: string;
  /** Null when the workspaces are in no project today. */
  sourceProjectId: string | null;
  targetProjectId: string;
  targetName: string;
  workspaceCount: number;
};

// Which of the four lookups worked. A lookup that failed must never read as "nothing is attached".
type Known = { teams: boolean; policies: boolean; collections: boolean; tags: boolean };
type Loaded = { impact: MoveImpactResult; known: Known; tagNames: Record<string, string> };

async function loadFacts(orgid: string, projectId: string): Promise<{ facts: ProjectFacts; known: Known }> {
  let tagsKnown = true;
  const [access, policies, collections, tags] = await Promise.all([
    projectService.listProjectTeams(orgid, projectId),
    projectService.listProjectPolicySets(orgid, projectId),
    projectService.listCollectionsForProject(orgid, projectId),
    projectService.listProjectDefaultTags(orgid, projectId).catch(() => {
      tagsKnown = false;
      return [];
    }),
  ]);
  return {
    known: {
      teams: !access.isError && !access.data?.restricted,
      policies: !policies.isError,
      collections: !collections.isError,
      tags: tagsKnown,
    },
    facts: {
      teams: access.isError ? [] : (access.data?.teams ?? []).map((item) => item.name),
      policySets: policies.isError
        ? []
        : (policies.data ?? [])
            .filter((set) => set.attachedToProject)
            .map((set) => ({ id: set.id, name: set.name, enforcementLevel: set.enforcementLevel })),
      collections: collections.isError
        ? []
        : (collections.data ?? []).filter((item) => item.referenceId).map((item) => ({ id: item.id, name: item.name })),
      defaultTags: tags.map((tag) => ({ tagId: tag.tagId, value: tag.value })),
    },
  };
}

const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? "" : "s"}`;

/** What moving workspaces into another project changes, shown before anything is moved. */
export default function MoveImpact({ orgid, sourceProjectId, targetProjectId, targetName, workspaceCount }: Props) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoaded(null);
    setFailed(false);
    Promise.all([
      sourceProjectId
        ? loadFacts(orgid, sourceProjectId)
        : Promise.resolve({
            facts: NO_FACTS,
            known: { teams: true, policies: true, collections: true, tags: true } as Known,
          }),
      loadFacts(orgid, targetProjectId),
      projectService.listOrganizationTags(orgid).catch(() => []),
    ])
      .then(([source, target, tags]) => {
        if (cancelled) return;
        setLoaded({
          impact: computeMoveImpact(sourceProjectId ? source.facts : null, target.facts),
          known: {
            teams: source.known.teams && target.known.teams,
            policies: source.known.policies && target.known.policies,
            collections: source.known.collections && target.known.collections,
            tags: source.known.tags && target.known.tags,
          },
          tagNames: Object.fromEntries(tags.map((tag) => [tag.id, tag.name])),
        });
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [orgid, sourceProjectId, targetProjectId]);

  if (failed) {
    return <Alert type="warning" showIcon message="The impact of this move could not be worked out." />;
  }
  if (!loaded) return <Skeleton active paragraph={{ rows: 3 }} />;

  const { impact, known, tagNames } = loaded;
  const rows: { label: string; text: string }[] = [];

  if (!known.teams) {
    rows.push({ label: "Access", text: "You can see these projects but not who has access to them." });
  } else if (impact.teamsGaining.length + impact.teamsLosing.length > 0) {
    const parts = [];
    if (impact.teamsGaining.length)
      parts.push(
        `${plural(impact.teamsGaining.length, "team")} ${impact.teamsGaining.length === 1 ? "gains" : "gain"} access (${impact.teamsGaining.join(", ")})`
      );
    if (impact.teamsLosing.length)
      parts.push(
        `${plural(impact.teamsLosing.length, "team")} ${impact.teamsLosing.length === 1 ? "loses" : "lose"} access (${impact.teamsLosing.join(", ")})`
      );
    rows.push({ label: "Access", text: parts.join(", ") });
  }
  if (!known.policies) {
    rows.push({
      label: "Policy",
      text: "Could not be checked, so policy sets that start or stop applying are not listed.",
    });
  }
  impact.policiesStarting.forEach((set) => {
    const level = enforcementLabel(set.enforcementLevel);
    rows.push({ label: "Policy", text: `${set.name} starts applying${level ? ` (${level})` : ""}` });
  });
  impact.policiesStopping.forEach((set) => rows.push({ label: "Policy", text: `${set.name} stops applying` }));
  if (!known.collections) {
    rows.push({
      label: "Variables",
      text: "Could not be checked, so collections that start or stop applying are not listed.",
    });
  }
  impact.collectionsStarting.forEach((item) =>
    rows.push({ label: "Variables", text: `Collection "${item.name}" starts applying` })
  );
  impact.collectionsStopping.forEach((item) =>
    rows.push({ label: "Variables", text: `Collection "${item.name}" stops applying` })
  );
  if (!known.tags) {
    rows.push({
      label: "Tags",
      text: "Could not be checked, so the default tags the workspaces inherit are not listed.",
    });
  }
  if (impact.tagsInherited.length) {
    const names = impact.tagsInherited.map(
      (tag) => `${tagNames[tag.tagId] ?? tag.tagId}${tag.value ? `:${tag.value}` : ""}`
    );
    rows.push({ label: "Tags", text: `Will inherit ${names.join(", ")}` });
  }

  return (
    <div
      className="project-move-impact"
      aria-label={`Impact of moving ${plural(workspaceCount, "workspace")} to ${targetName}`}
    >
      {hasAnyImpact(impact) || rows.length > 0 ? (
        <dl>
          {rows.map((row, index) => (
            <div key={index} className="project-move-impact-row">
              <dt>{row.label}</dt>
              <dd>{row.text}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <Alert type="info" showIcon message="Nothing changes for access, policy, variables or tags." />
      )}
      <p className="project-move-impact-note">Access granted directly on a workspace is kept.</p>
    </div>
  );
}
