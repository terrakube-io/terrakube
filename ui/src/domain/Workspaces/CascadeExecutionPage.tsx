import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "@/config/actionTypes";
import axiosInstance from "@/config/axiosConfig";
import { CascadeGraph } from "./CascadeGraph";

/**
 * A single cascade's own page, with the same frame (breadcrumbs, title, subtitle) as the other
 * organization pages. It has a shareable, bookmarkable URL and more room than a Drawer gives it.
 * The organization comes from the route when it is there, otherwise from sessionStorage, same as
 * Details.tsx's own workspace page does for a bare /workspaces/:id URL.
 */
export const CascadeExecutionPage = () => {
  const { orgid, id: workspaceId, cascadeId } = useParams<{ orgid?: string; id: string; cascadeId: string }>();
  const organizationId = orgid ?? sessionStorage.getItem(ORGANIZATION_ARCHIVE) ?? "";
  const organizationName = sessionStorage.getItem(ORGANIZATION_NAME) ?? "Organization";
  const [workspaceName, setWorkspaceName] = useState<string>();

  useEffect(() => {
    if (!workspaceId) return;
    let active = true;
    axiosInstance
      .get(`workspace/${workspaceId}`)
      .then((response) => active && setWorkspaceName(response.data.data.attributes.name))
      .catch(() => {
        // The name only decorates the breadcrumb and subtitle; the page works without it.
      });
    return () => {
      active = false;
    };
  }, [workspaceId]);

  if (!workspaceId || !cascadeId) {
    return null;
  }

  const workspaceLabel = workspaceName ?? "Workspace";

  return (
    <PageWrapper
      title="Run cascade"
      subTitle={`Started from ${workspaceLabel}. Every workspace this change reached, with the status of each run.`}
      breadcrumbs={[
        { label: organizationName, path: `/organizations/${organizationId}/workspaces` },
        { label: "Run cascades", path: `/organizations/${organizationId}/cascades` },
        { label: workspaceLabel, path: `/organizations/${organizationId}/workspaces/${workspaceId}/cascades` },
        { label: "Cascade" },
      ]}
    >
      <CascadeGraph cascadeId={cascadeId} organizationId={organizationId} />
    </PageWrapper>
  );
};

export default CascadeExecutionPage;
