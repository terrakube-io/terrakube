import { JobStatus } from "../../domain/types";

export type WorkspaceListItem = {
  id: string;
  lastRun?: string;
  lastStatus?: JobStatus;
  name: string;
  description?: string;
  branch?: string;
  iacType: string;
  source: string;
  normalizedSource?: string;
  terraformVersion?: string;
  tags?: string[];
  projectId?: string;
  projectName?: string;
  locked?: boolean;
};

export type ListWorkspacesResponse = {
  organizationId: string;
  organizationName: string;
  workspaces: WorkspaceListItem[];
};

export type WorkspacePageInfo = {
  endCursor?: string;
  hasNextPage: boolean;
  totalRecords: number;
};

export type WorkspacePageResponse = {
  workspaces: WorkspaceListItem[];
  pageInfo: WorkspacePageInfo;
  statusCounts: Record<string, number>;
};

export type WorkspacePageRequest = {
  organizationId: string;
  first: number;
  after?: string;
  search?: string;
  status?: string;
  tagIds?: string[];
  projectId?: string | null;
  sort:
    | "name_asc"
    | "name_desc"
    | "lastRun_desc"
    | "lastRun_asc"
    | "status"
    | "source_asc"
    | "source_desc"
    | "terraformVersion_asc"
    | "terraformVersion_desc";
};
