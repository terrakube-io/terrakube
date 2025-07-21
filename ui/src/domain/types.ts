// Shared

import { AuditFieldBase } from "@/modules/types";

export type RelationshipItem = {
  data: { type: string; id: string };
};
export type RelationshipArray = {
  data: RelationshipItem[];
};
export type IncludedItem<T> = {
  type: string;
} & T;
export type TofuRelease = {
  tag_name: string;
};

export type AttributeWrapped<T> = {
  id: string;
  attributes: T;
};

// Organization
export type Organization = {
  id: string;
  attributes: OrganizationAttributes;
};

export type OrganizationAttributes = {
  description?: string;
  name: string;
  executionMode?: string;
  icon?: string;
};

export type ApiResponse<T> = {
  data: T;
};

export type FlatOrganization = {
  id: string;
} & OrganizationAttributes;

// Templates

export type Template = {
  id: string;
  attributes: TemplateAttributes;
};
export type TemplateAttributes = {
  name: string;
  description: string;
  tcl: string;
  image: string;
  color?: string;
};

// Jobs
export type Job = {
  id: string;
  attributes: JobAttributes;
};

export enum JobStatus {
  Pending = "pending",
  WaitingApproval = "waitingApproval",
  Approved = "approved",
  Queue = "queue",
  Running = "running",
  Completed = "completed",
  NoChanges = "noChanges",
  NotExecuted = "notExecuted",
  Rejected = "rejected",
  Cancelled = "cancelled",
  Failed = "failed",
  Unknown = "unknown",
}

export enum JobVia {
  Ui = "UI",
  Cli = "CLI",
  Github = "Github",
  Gitlab = "Gitlab",
  Bitbucket = "Bitbucket",
  Schedule = "Schedule",
}

export type JobAttributes = {
  status: JobStatus;
  via: JobVia;
  output: string;
  approvalTeam: string;
  commitId: string;
} & AuditFieldBase;

export type JobStep = {
  id: string;
  name: string;
  stepNumber: number;
  status: JobStatus;
  output: string;
  outputLog: string;
};
export type FlatJob = {
  id: string;
  title: string;
  status: JobStatus;
  statusColor: string;
  latestChange: string;
  commitId?: string;
  createdBy: string;
  via?: JobVia;
};
// VCS

export enum VcsType {
  UNKNOWN = "UNKNOWN",
  GITHUB = "GITHUB",
  GITLAB = "GITLAB",
  BITBUCKET = "BITBUCKET",
  AZURE_DEVOPS = "AZURE_DEVOPS",
  PUBLIC = "PUBLIC",
}

export enum VcsTypeExtended {
  GITHUB = "GITHUB",
  GITHUB_ENTERPRISE = "GITHUB_ENTERPRISE",
  GITLAB = "GITLAB",
  GITLAB_ENTERPRISE = "GITLAB_ENTERPRISE",
  GITLAB_COMMUNITY = "GITLAB_COMMUNITY",
  BITBUCKET = "BITBUCKET",
  BITBUCKET_SERVER = "BITBUCKET_SERVER",
  AZURE_DEVOPS = "AZURE_DEVOPS",
  AZURE_DEVOPS_SERVER = "AZURE_DEVOPS_SERVER",
  PUBLIC = "PUBLIC",
}

export type VcsModel = {
  id: string;
  attributes: VcsAttributes;
};

export type VcsAttributes = {
  name: string;
  vcsType: VcsType;
  description: string;
  clientId: string;
  callback: string;
  endpoint: string;
  apiUrl: string;
  connectionType: VcsConnectionType;
  status: VcsStatus;
} & AuditFieldBase;
export enum VcsConnectionType {
  OAUTH = "OAUTH",
  STANDALONE = "STANDALONE",
}
export enum VcsStatus {
  PENDING = "PENDING",
  COMPLETED = "COMPLETED",
  ERROR = "ERROR",
}

// SSH Keys

export type SshKey = {
  id: string;
  attributes: SshKeyAttributes;
};
export type SshKeyAttributes = {
  name: string;
  description: string;
  sshType: string;
};

// Modules
export type ModuleModel = {
  id: string;
  type: string;
  attributes: ModuleAttributes;
};

export type ModuleAttributes = {
  description: string;
  downloadQuantity: number;
  name: string;
  provider: string;
  source: string;
  folder?: string;
  latestVersion: string;
  versions: string[];
  registryPath: string;
  tagPrefix?: string;
} & AuditFieldBase;

export type ModuleVersionAttributes = {
  version: string;
  commit: string;
};

export type FlatModule = {
  id: string;
} & ModuleAttributes;

// Team

export type Team = {
  id: string;
  attributes: TeamAttributes;
};

export type TeamAttributes = {
  manageCollection: boolean;
  manageJob: boolean;
  manageModule: boolean;
  manageProvider: boolean;
  manageState: boolean;
  manageTemplate: boolean;
  manageVcs: boolean;
  manageWorkspace: boolean;
  name: string;
};

// Token
export type TeamToken = {
  id: string;
  days: number;
  hours: number;
  minutes: number;
  group: string;
  description: string;
  deleted: boolean;
} & AuditFieldBase;

// Variables

export type Variable = {
  id: string;
  attributes: VariableAttributes;
};
export type VariableAttributes = {
  key: string;
  value: string;
  hcl: boolean;
  category: string;
  description: string;
  sensitive: boolean;
};

export type FlatVariable = {
  id: string;
} & VariableAttributes;

export type CreateVariableForm = {
  sensitive: boolean;
} & UpdateVariableForm;

export type UpdateVariableForm = {
  key: string;
  value: string;
  hcl: boolean;
  category: string;
  description: string;
};

// Tags
export type Tag = {
  id: string;
  attributes: TagAttributes;
};
export type TagAttributes = {
  name: string;
};
export type ApiWorkspaceTag = {
  id: string;
  attributes: {
    tagId: string;
    name: string;
  } & AuditFieldBase;
  relationships: any;
  type: string;
};

// Actions
export type Action = {
  id: string;
  attributes: ActionAttributes;
};
export type ActionAttributes = {
  name: string;
  label: string;
  action: string;
  type: string;
  category: string;
  version: string;
  active: boolean;
  displayCriteria: string;
};
export type ActionWithSettings = Action & { settings?: any };

// Schedules
export type Schedule = {
  id: string;
  attributes: ScheduleAttributes;
};

export type ScheduleAttributes = {
  cron: string;
  description?: string;
  enabled: boolean;
  tcl?: string;
  templateReference: string;
  name: string;
} & AuditFieldBase;

export type FlatSchedule = {
  id: string;
} & ScheduleAttributes;

// Workspaces
export type Workspace = {
  id: string;
  attributes: WorkspaceAttributes;
  relationships: {
    organization: RelationshipItem;
    webhook?: RelationshipItem;
    agent?: RelationshipItem;
    history?: RelationshipArray;
  };
};
export type WorkspaceAttributes = {
  branch: string;
  defaultTemplate?: string;
  deleted: boolean;
  description?: string;
  executionMode: string;
  folder?: string;
  iacType: string;
  lockDescription?: string;
  locked: boolean;
  moduleSshKey?: string;
  name: string;
  source: string;
  terraformVersion: string;
} & AuditFieldBase;

export type Webhook = {
  id: string;
  attributes: WebhookAttributes;
};
export type WebhookAttributes = {
  remoteHookId: string;
};
export enum WebhookEventType {
  PUSH = "PUSH",
  PULL_REQUEST = "PULL_REQUEST",
  PING = "PING",
}
export type WebhookEvent = {
  id: string;
  attributes: WebhookEventAttributes;
};
export type WebhookEventAttributes = {
  branch: string;
  path: string;
  templateId: string;
  priority: number;
  event: WebhookEventType;
};

// Agent
export type Agent = {
  id: string;
  attributes: AgentAttributes;
};
export type AgentAttributes = {
  name: string;
  description: string;
  url: string;
};

// States
export type Resource = {
  name: string;
  provider: string;
  type: string;
  values: Record<string, any>;
  depends_on: string;
  showDrawer: (data: Resource) => void;
};
export type ErrorResource = {
  name: string;
  provider: string;
  type: unknown;
};

// History

export type JobHistory = AttributeWrapped<JobHistoryAttributes>;
export type JobHistoryAttributes = {
  jobReference: string;
  lineage: string;
  md5: string;
  output: string;
  serial: number;
} & AuditFieldBase;

export type FlatJobHistory = {
  id: string;
  title: string;
  relativeDate: string;
  createdDate: string;
} & JobHistoryAttributes;

// State output
export type StateOutput = {
  format_version: string;
  terraform_version: string;
  values: {
    output: {
      [key: string]: StateOutputValue;
    };
    root_module: {
      resources: StateOutputResource[];
      child_modules: any[];
    };
  };
};
export type StateOutputValue = { sensitive?: boolean; value: string; type: string };

export type StateOutputResource = {
  address: string;
  mode: string;
  type: string;
  name: string;
  provider_name: string;
  schema_version: number;
  values: Record<string, any>;
  depends_on: any;
};
