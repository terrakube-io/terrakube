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
    return "no-op";
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
