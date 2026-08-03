export type PlanChange = {
  address?: string;
  resourceType?: string;
  resourceName?: string;
  moduleAddress?: string;
  action?: string;
  actions?: string[];
  before?: unknown;
  beforeSensitive?: unknown;
  changedSensitive?: unknown;
  after?: unknown;
  afterSensitive?: unknown;
  afterUnknown?: unknown;
  importing?: { id?: string };
};

export type StructuredPlanOutputByStep = Record<string, PlanChange[]>;

const isRecord = (value: unknown): value is Record<string, unknown> => {
  if (value === null) {
    return false;
  }

  if (typeof value !== "object") {
    return false;
  }

  return true;
};

const toOptionalString = (value: unknown): string | undefined => {
  if (typeof value !== "string") {
    return undefined;
  }

  if (value.trim().length === 0) {
    return undefined;
  }

  return value;
};

const toStringArray = (value: unknown): string[] => {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0);
};

export const getPlanChangeActionLabel = (actions: string[] = [], fallback?: string): string => {
  const normalizedActions = toStringArray(actions);

  if (normalizedActions.includes("delete") && normalizedActions.includes("create")) {
    return "replace";
  }

  if (normalizedActions.includes("create")) {
    return "create";
  }

  if (normalizedActions.includes("delete")) {
    return "delete";
  }

  if (normalizedActions.includes("update")) {
    return "update";
  }

  if (normalizedActions.includes("read")) {
    return "read";
  }

  if (normalizedActions.includes("no-op")) {
    // Terraform's plan JSON always reports a clean import as actions: ["no-op"] -
    // "import" only ever shows up via the separate `importing` field, which the
    // backend translates into this `fallback` value. Check that before falling
    // back to a generic no-op, or every import would render as a no-op instead.
    const fallbackValue = toOptionalString(fallback);
    if (fallbackValue === "import") {
      return "import";
    }

    return "no-op";
  }

  if (normalizedActions.includes("import")) {
    return "import";
  }

  const fallbackValue = toOptionalString(fallback);
  if (fallbackValue) {
    return fallbackValue;
  }

  return "unknown";
};

export const getPlanChangeActionColor = (actions: string[] = [], fallback?: string): string => {
  const actionLabel = getPlanChangeActionLabel(actions, fallback);

  if (actionLabel === "create") {
    return "green";
  }

  if (actionLabel === "delete") {
    return "red";
  }

  if (actionLabel === "update") {
    return "blue";
  }

  if (actionLabel === "replace") {
    return "orange";
  }

  return "default";
};

const normalizePlanChange = (value: unknown): PlanChange | null => {
  if (!isRecord(value)) {
    return null;
  }

  const actions = toStringArray(value.actions);
  const action = getPlanChangeActionLabel(actions, toOptionalString(value.action));

  const rawImporting = value.importing;
  const importing =
    isRecord(rawImporting)
      ? { id: typeof rawImporting.id === "string" ? rawImporting.id : undefined }
      : undefined;

  return {
    address: toOptionalString(value.address),
    resourceType: toOptionalString(value.resourceType),
    resourceName: toOptionalString(value.resourceName),
    moduleAddress: toOptionalString(value.moduleAddress),
    action,
    actions,
    before: value.before,
    beforeSensitive: value.beforeSensitive,
    changedSensitive: value.changedSensitive,
    after: value.after,
    afterSensitive: value.afterSensitive,
    afterUnknown: value.afterUnknown,
    importing,
  };
};

export const normalizeStructuredPlanOutput = (value: unknown): StructuredPlanOutputByStep => {
  if (!isRecord(value)) {
    return {};
  }

  const normalizedOutput: StructuredPlanOutputByStep = {};

  Object.entries(value).forEach(([stepId, rawChanges]) => {
    if (!Array.isArray(rawChanges)) {
      return;
    }

    const normalizedChanges = rawChanges
      .map((rawChange) => normalizePlanChange(rawChange))
      .filter((change): change is PlanChange => change !== null);

    normalizedOutput[stepId] = normalizedChanges;
  });

  return normalizedOutput;
};

export type ApplyResourceStatus = "pending" | "applying" | "applied" | "errored";

export type ApplyChange = PlanChange & {
  status: ApplyResourceStatus;
  error?: string;
};

export type StructuredApplyOutputByStep = Record<string, ApplyChange[]>;

const APPLY_STATUSES: ApplyResourceStatus[] = ["pending", "applying", "applied", "errored"];

const toApplyStatus = (value: unknown): ApplyResourceStatus => {
  if (typeof value === "string" && (APPLY_STATUSES as string[]).includes(value)) {
    return value as ApplyResourceStatus;
  }

  return "pending";
};

const normalizeApplyChange = (value: unknown): ApplyChange | null => {
  const planChange = normalizePlanChange(value);
  if (planChange === null) {
    return null;
  }

  const record = value as Record<string, unknown>;

  return {
    ...planChange,
    status: toApplyStatus(record.status),
    error: toOptionalString(record.error),
  };
};

export const normalizeStructuredApplyOutput = (value: unknown): StructuredApplyOutputByStep => {
  if (!isRecord(value)) {
    return {};
  }

  const normalizedOutput: StructuredApplyOutputByStep = {};

  Object.entries(value).forEach(([stepId, rawChanges]) => {
    if (!Array.isArray(rawChanges)) {
      return;
    }

    const normalizedChanges = rawChanges
      .map((rawChange) => normalizeApplyChange(rawChange))
      .filter((change): change is ApplyChange => change !== null);

    normalizedOutput[stepId] = normalizedChanges;
  });

  return normalizedOutput;
};

export type TerraformOutputValue = {
  name: string;
  value: unknown;
  sensitive: boolean;
  type?: unknown;
};

export type StructuredOutputsByStep = Record<string, TerraformOutputValue[]>;

const normalizeTerraformOutput = (value: unknown): TerraformOutputValue | null => {
  if (!isRecord(value)) {
    return null;
  }

  const name = toOptionalString(value.name);
  if (!name) {
    return null;
  }

  return {
    name,
    value: value.value,
    sensitive: value.sensitive === true,
    type: value.type,
  };
};

export const normalizeStructuredOutputs = (value: unknown): StructuredOutputsByStep => {
  if (!isRecord(value)) {
    return {};
  }

  const normalizedOutputs: StructuredOutputsByStep = {};

  Object.entries(value).forEach(([stepId, rawOutputs]) => {
    if (!Array.isArray(rawOutputs)) {
      return;
    }

    const normalizedStepOutputs = rawOutputs
      .map((rawOutput) => normalizeTerraformOutput(rawOutput))
      .filter((output): output is TerraformOutputValue => output !== null);

    normalizedOutputs[stepId] = normalizedStepOutputs;
  });

  return normalizedOutputs;
};

export const normalizeUITemplates = (value: unknown): Record<string, string> => {
  if (!isRecord(value)) {
    return {};
  }

  const normalizedTemplates: Record<string, string> = {};

  Object.entries(value).forEach(([stepId, template]) => {
    const normalizedTemplate = toOptionalString(template);
    if (!normalizedTemplate) {
      return;
    }

    normalizedTemplates[stepId] = normalizedTemplate;
  });

  return normalizedTemplates;
};
