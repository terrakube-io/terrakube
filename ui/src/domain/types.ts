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

/**
 * A resource narrowed to the attributes a `fields[type]=...` query parameter asked for.
 *
 * Naming any field for a type also suppresses that resource's `relationships` block, so both are
 * removed here. Prefer deriving this through {@link sparseFields} rather than writing it directly,
 * so the shape cannot disagree with the request that produced it.
 */
export type Sparse<T extends { attributes: object }, K extends keyof T["attributes"]> = Omit<
  T,
  "attributes" | "relationships"
> & {
  attributes: Pick<T["attributes"], K>;
};

declare const RESOURCE: unique symbol;

/** A `fields[type]=...` query fragment that carries the resource shape it produces. */
export type FieldSet<R> = string & { readonly [RESOURCE]: R };

/** The resource shape a {@link FieldSet} yields. */
export type SparseOf<F> = F extends FieldSet<infer R> ? R : never;

type AttributeName<T extends { attributes: object }> = keyof T["attributes"] & string;

/**
 * Builds a `fields[type]=...` fragment and the response shape it produces from one field list, so
 * the two cannot drift. Interpolates into a URL as an ordinary string.
 *
 *     const ORGANIZATION_FIELDS = sparseFields<Organization>("organization")("name");
 *     type SparseOrganization = SparseOf<typeof ORGANIZATION_FIELDS>;
 *     axiosInstance.get(`organization/${id}?${ORGANIZATION_FIELDS}`);
 *
 * Field names are checked against the resource, so a typo fails to compile rather than reaching the
 * server and quietly returning a resource without it. Applied in two calls because TypeScript
 * cannot infer the field list while the resource type is given explicitly.
 */
export function sparseFields<T extends { attributes: object }>(type: string) {
  return <const K extends readonly AttributeName<T>[]>(...fields: K): FieldSet<Sparse<T, K[number]>> =>
    `fields[${type}]=${fields.join(",")}` as FieldSet<Sparse<T, K[number]>>;
}

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
  workspaceCount?: number;
  workspaceStatusCounts?: Record<string, number>;
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
  prNumber?: number;
  prCommentError?: string;
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
  prNumber?: number;
  prCommentError?: string;
};
// VCS

export enum VcsType {
  UNKNOWN = "UNKNOWN",
  GITHUB = "GITHUB",
  GITLAB = "GITLAB",
  BITBUCKET = "BITBUCKET",
  AZURE_DEVOPS = "AZURE_DEVOPS",
  AZURE_SP_MI = "AZURE_SP_MI",
  PUBLIC = "PUBLIC",
}

export enum VcsTypeExtended {
  GITHUB = "GITHUB",
  GITHUB_APP = "GITHUB_APP",
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

export type VcsRepositoryGroup = {
  id: string;
  name: string;
};

export type VcsRepositorySummary = {
  name: string;
  fullName: string;
  group: string;
  url: string;
  privateRepo: boolean;
  defaultBranch?: string;
};

export type VcsRepositoryPage = {
  items: VcsRepositorySummary[];
  hasMore: boolean;
  page: number;
};
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

export type TeamRole = "admin" | "write" | "plan" | "read" | "custom";

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
  role?: TeamRole;
  planJob?: boolean;
  approveJob?: boolean;
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

export type VariableCategory = "TERRAFORM" | "ENV";

export type Variable = {
  id: string;
  attributes: VariableAttributes;
};
export type VariableAttributes = {
  key: string;
  value: string;
  hcl: boolean;
  // Legacy rows may still have no category until remediated; see issue #3395.
  category: VariableCategory | null;
  description: string;
  sensitive: boolean;
  incomplete: boolean;
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
  category: VariableCategory;
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

// Federated
export type Federated = {
  id: string;
  attributes: FederatedAttributes;
};
export type FederatedAttributes = {
  name: string;
  issuerUrl: string;
  audience: string;
};
export type FederatedClaim = {
  id: string;
  attributes: FederatedClaimAttributes;
};
export type FederatedClaimAttributes = {
  claimKey: string;
  claimValue: string;
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

// Projects
export type Project = {
  id: string;
  attributes: ProjectAttributes;
  relationships: { organization: RelationshipItem };
};

export type ProjectAttributes = {
  name: string;
  description?: string;
} & AuditFieldBase;

export type ProjectModel = {
  id: string;
  name: string;
  description?: string;
};

// Workspaces
export type Workspace = {
  id: string;
  attributes: WorkspaceAttributes;
  relationships: {
    organization: RelationshipItem;
    webhook?: RelationshipItem;
    agent?: RelationshipItem;
    project?: RelationshipItem;
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
  globalRemoteState?: boolean;
  sharedIds?: string;
} & AuditFieldBase;

export type Webhook = {
  id: string;
  attributes: WebhookAttributes;
};
export type WebhookAttributes = {
  remoteHookId: string;
  migratedV2: boolean;
};
export enum WebhookEventType {
  PUSH = "PUSH",
  PULL_REQUEST = "PULL_REQUEST",
  PR_COMMENT = "PR_COMMENT",
  PING = "PING",
}
export enum WebhookEventPathType {
  PATTERN = "PATTERN",
  REGEX = "REGEX",
}
export type WebhookEvent = {
  id: string;
  attributes: WebhookEventAttributes;
};
export type WebhookEventAttributes = {
  branch: string;
  path: string;
  pathType: WebhookEventPathType;
  templateId: string;
  priority: number;
  event: WebhookEventType;
  prWorkflowEnabled: boolean;
  prApplyEnabled: boolean;
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
