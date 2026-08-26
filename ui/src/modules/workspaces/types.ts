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
