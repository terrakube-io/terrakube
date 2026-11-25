import { Button, Result, Spin } from "antd";
import { useEffect, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import { GoTarget, goDestination, parseGoPath } from "./goPath";
import { findProjectByName, findWorkspaceByName, resolveOrganization } from "./navigationService";

type Outcome =
  | { state: "working" }
  | { state: "found"; to: string }
  | { state: "missing"; what: string; orgId?: string }
  | { state: "failed" };

/**
 * /go/<organization>[/projects/<name> | /workspaces/<name>[/runs[/<number>]]] sends a name to the page it names.
 * A GitOps repository, a pull request comment or Backstage only knows names, never the ids the app uses.
 */
export default function GoRedirect() {
  const { org, "*": rest = "" } = useParams();
  const [outcome, setOutcome] = useState<Outcome>({ state: "working" });

  useEffect(() => {
    let cancelled = false;
    const target: GoTarget | null = parseGoPath(rest);
    if (!org || target === null) {
      setOutcome({ state: "missing", what: "that link" });
      return;
    }
    setOutcome({ state: "working" });
    (async () => {
      try {
        const organization = await resolveOrganization(org);
        if (!organization) return { state: "missing", what: `the organization "${org}"` } as Outcome;
        if (target.kind === "organization") {
          return { state: "found", to: goDestination(organization.id, target, {}) } as Outcome;
        }
        if (target.kind === "project") {
          const project = await findProjectByName(organization.id, target.name);
          return project
            ? ({ state: "found", to: goDestination(organization.id, target, { projectId: project.id }) } as Outcome)
            : ({
                state: "missing",
                what: `the project "${target.name}" in ${organization.name}`,
                orgId: organization.id,
              } as Outcome);
        }
        const workspace = await findWorkspaceByName(organization.id, target.name);
        return workspace
          ? ({ state: "found", to: goDestination(organization.id, target, { workspaceId: workspace.id }) } as Outcome)
          : ({
              state: "missing",
              what: `the workspace "${target.name}" in ${organization.name}`,
              orgId: organization.id,
            } as Outcome);
      } catch {
        return { state: "failed" } as Outcome;
      }
    })().then((result) => {
      if (!cancelled) setOutcome(result);
    });
    return () => {
      cancelled = true;
    };
  }, [org, rest]);

  if (outcome.state === "found") return <Navigate to={outcome.to} replace />;
  if (outcome.state === "working") {
    return (
      <div className="go-redirect" role="status" aria-label="Finding the page">
        <Spin size="large" />
      </div>
    );
  }
  if (outcome.state === "failed") {
    return (
      <Result
        status="error"
        title="That link could not be followed"
        subTitle="The request failed. Try again in a moment."
        extra={<Button onClick={() => window.location.reload()}>Try again</Button>}
      />
    );
  }
  return (
    <Result
      status="404"
      title={`Could not find ${outcome.what}`}
      subTitle="It may have been renamed or removed, or you may not have access to it."
      extra={
        outcome.orgId ? (
          <Link to={`/organizations/${outcome.orgId}/workspaces`}>Search this organization&apos;s workspaces</Link>
        ) : (
          <Link to="/organizations">Go to your organizations</Link>
        )
      }
    />
  );
}
