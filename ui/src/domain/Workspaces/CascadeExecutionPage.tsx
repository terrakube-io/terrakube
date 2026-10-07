import { Typography } from "antd";
import { Link, useParams } from "react-router-dom";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";
import { CascadeGraph } from "./CascadeGraph";

/**
 * A single cascade's own page - promoted out of Cascades.tsx's Drawer so a running cascade has
 * a shareable, bookmarkable URL and more room than a Drawer gives it. organizationId comes from
 * sessionStorage, same as Details.tsx's own workspace page does for a bare /workspaces/:id URL -
 * AppLayout's own effect has already resolved and stored it by the time this renders.
 */
export const CascadeExecutionPage = () => {
  const { id: workspaceId, cascadeId } = useParams<{ id: string; cascadeId: string }>();
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE) ?? "";

  if (!workspaceId || !cascadeId) {
    return null;
  }

  return (
    <div>
      <Typography.Paragraph>
        <Link to={`/organizations/${organizationId}/workspaces/${workspaceId}/cascades`}>&larr; Back to cascades</Link>
      </Typography.Paragraph>
      <Typography.Title level={2} style={{ margin: 0 }}>
        Cascade
      </Typography.Title>
      <div style={{ marginTop: 16 }}>
        <CascadeGraph cascadeId={cascadeId} organizationId={organizationId} />
      </div>
    </div>
  );
};

export default CascadeExecutionPage;
