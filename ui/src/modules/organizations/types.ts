export type OrganizationModel = {
  id: string;
  description?: string;
  name: string;
  executionMode?: string;
  icon?: string;
  workspaceCount?: number;
};

export type TagModel = {
  id: string;
  name: string;
};
