import { Alert, Button, Skeleton } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { EmptyState } from "@/components/feedback/EmptyState";

/** Placeholders sized like the final cards or rows, so the page does not jump when data arrives. */
export function ProjectsSkeleton({ view }: { view: "cards" | "table" }) {
  if (view === "cards") {
    return (
      <div className="projects-card-grid" aria-busy="true" aria-label="Loading projects">
        {Array.from({ length: 6 }, (_, index) => (
          <div key={index} className="project-card">
            <Skeleton active title={{ width: "55%" }} paragraph={{ rows: 3 }} />
          </div>
        ))}
      </div>
    );
  }
  return (
    <div aria-busy="true" aria-label="Loading projects">
      {Array.from({ length: 6 }, (_, index) => (
        <Skeleton key={index} active title={false} paragraph={{ rows: 1, width: "100%" }} />
      ))}
    </div>
  );
}

export function NoProjects({ canCreate, onCreate }: { canCreate: boolean; onCreate: () => void }) {
  return (
    <EmptyState description="A project groups workspaces so a team shares access, policy and configuration.">
      <Button type="primary" icon={<PlusOutlined />} onClick={onCreate} disabled={!canCreate}>
        New project
      </Button>
    </EmptyState>
  );
}

export function NoMatch({ search, onClear }: { search: string; onClear: () => void }) {
  return (
    <EmptyState description={search ? `No projects match "${search}".` : "No projects match these filters."}>
      <Button onClick={onClear}>{search ? "Clear search" : "Clear filters"}</Button>
    </EmptyState>
  );
}

/** Counts that could not be loaded are never drawn as 0. */
export function CountsUnavailable({ onRetry }: { onRetry: () => void }) {
  return (
    <Alert
      type="warning"
      showIcon
      className="project-overview-alert"
      message="Counts unavailable."
      action={
        <Button size="small" onClick={onRetry}>
          Retry
        </Button>
      }
    />
  );
}
