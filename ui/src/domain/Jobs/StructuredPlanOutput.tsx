import {
  CheckCircleOutlined,
  CopyOutlined,
  DeploymentUnitOutlined,
  DownloadOutlined,
  DownOutlined,
  FilterOutlined,
  LoadingOutlined,
  RightOutlined,
} from "@ant-design/icons";
import { Empty, message } from "antd";
import type { ChangeEvent, ReactNode } from "react";
import { useEffect, useMemo, useState } from "react";
import { FaAws, FaDiscord, FaDocker, FaGithub, FaGitlab, FaGoogle, FaMicrosoft, FaSlack } from "react-icons/fa6";
import {
  SiCloudflare,
  SiConsul,
  SiDatadog,
  SiDigitalocean,
  SiElastic,
  SiGrafana,
  SiHelm,
  SiKubernetes,
  SiMongodb,
  SiMysql,
  SiNewrelic,
  SiOkta,
  SiPagerduty,
  SiPostgresql,
  SiSnowflake,
  SiTerraform,
  SiVault,
  SiVmware,
} from "react-icons/si";
import { stripAnsi } from "./stripAnsi";
import { ApplyChange, Diagnostic, PlanChange, TerraformOutputValue, getPlanChangeActionLabel } from "./structuredPlan";
import "./StructuredPlanOutput.css";

type Props = {
  changes: PlanChange[] | ApplyChange[];
  outputLog?: string;
  applyMode?: boolean;
  outputs?: TerraformOutputValue[];
  jobDiagnostics?: Diagnostic[];
  // Whether the owning step is still executing. An empty `changes` array is indistinguishable
  // from "plan finished, nothing differs" while the step is running - the backend just hasn't
  // streamed any resource changes yet. Defaults to false (finished) so existing call sites that
  // only ever render this component once a step's output is final keep their current behavior.
  isStepRunning?: boolean;
};

type ActionName = "create" | "update" | "replace" | "delete" | "read" | "import" | "unknown" | "no-op" | "ephemeral";

type DiffRow = {
  key: string;
  label: string;
  kind: "group" | "added" | "removed" | "changed" | "unknown" | "sensitive" | "unchanged";
  before?: unknown;
  after?: unknown;
  children?: DiffRow[];
  hiddenCount?: number;
  // True when this group's children came from parsing a JSON-encoded string value (e.g. an IAM
  // policy document) rather than from a value that was already a nested object/array - drives the
  // "jsonencode({ ... })" wrapper hint so it's clear the braces aren't part of the real schema.
  jsonEncoded?: boolean;
};

type CollectionEntry = {
  identity: string;
  value: unknown;
  unknown: unknown;
  beforeSensitive: unknown;
  afterSensitive: unknown;
  changedSensitive: unknown;
};

type BuildCollectionEntriesArgs = {
  values: unknown[];
  unknownValues?: unknown[];
  beforeSensitiveValues?: unknown[];
  afterSensitiveValues?: unknown[];
  changedSensitiveValues?: unknown[];
};

type DiffResult = {
  rows: DiffRow[];
  hiddenCount: number;
  // Propagated up so the caller that wraps these rows in a group row (see buildDiffRows) knows to
  // tag that group as jsonEncoded, since the decoding happens one recursion level below the wrap.
  wasJsonEncoded?: boolean;
};

type SummaryCounts = {
  create: number;
  update: number;
  delete: number;
  read: number;
  import: number;
  unknown: number;
  // Resources whose action is specifically "replace" - create/delete above also count a replace
  // toward each of them (matching Terraform's own "Plan: N to add, N to destroy" convention,
  // where a replace is double-counted), so this can't be derived as create+delete once there are
  // *also* pure creates or deletes in the same plan - it needs its own tally.
  replace: number;
};

type PreparedChangeRow = {
  key: string;
  panelId: string;
  change: PlanChange | ApplyChange;
  action: ActionName;
  resourceLabel: string;
  providerName: string;
  isDataSource: boolean;
  diff: DiffResult;
  visibleChanges: number;
  hiddenCount: number;
  applyStatus?: ApplyChange["status"];
  applyDiagnostics?: ApplyChange["diagnostics"];
  applyElapsedSeconds?: ApplyChange["elapsedSeconds"];
  applyCurrentProvisioner?: ApplyChange["currentProvisioner"];
  applyProvisionerOutput?: ApplyChange["provisionerOutput"];
  driftAction?: PlanChange["driftAction"];
};

type SummarySegment = {
  key: "create" | "update" | "delete" | "import";
  label: string;
  count: number;
  symbol: string;
};

const actionMeta: Record<
  ActionName,
  {
    displayLabel: string;
    filterLabel: string;
    symbol: string;
    className: string;
  }
> = {
  create: {
    displayLabel: "create",
    filterLabel: "Create",
    symbol: "+",
    className: "create",
  },
  update: {
    displayLabel: "update",
    filterLabel: "Change",
    symbol: "~",
    className: "update",
  },
  replace: {
    displayLabel: "replace",
    filterLabel: "Replace",
    symbol: "-/+",
    className: "replace",
  },
  delete: {
    displayLabel: "destroy",
    filterLabel: "Destroy",
    symbol: "-",
    className: "delete",
  },
  read: {
    displayLabel: "read",
    filterLabel: "Read",
    symbol: "?",
    className: "read",
  },
  import: {
    displayLabel: "import",
    filterLabel: "Import",
    symbol: "i",
    className: "import",
  },
  unknown: {
    displayLabel: "unknown",
    filterLabel: "Unknown",
    symbol: "?",
    className: "unknown",
  },
  "no-op": {
    displayLabel: "no-op",
    filterLabel: "No-op",
    symbol: "=",
    className: "unknown",
  },
  ephemeral: {
    displayLabel: "ephemeral",
    filterLabel: "Ephemeral",
    symbol: "○",
    className: "ephemeral",
  },
};

// Verb forms for the in-progress/done apply-status badge, so a destroy reads "destroying" /
// "destroyed" rather than the generic "applying" / "applied" every other action shares.
const applyStatusVerbs: Record<ActionName, { gerund: string; done: string }> = {
  create: { gerund: "creating", done: "created" },
  update: { gerund: "modifying", done: "modified" },
  replace: { gerund: "replacing", done: "replaced" },
  delete: { gerund: "destroying", done: "destroyed" },
  read: { gerund: "reading", done: "refreshed" },
  import: { gerund: "importing", done: "imported" },
  unknown: { gerund: "applying", done: "applied" },
  // "unchanged", not "applied" - a no-op resource had nothing done to it (it's why it never
  // shows a diff), so labeling it the same as a resource that was actually just created/updated
  // would misleadingly imply an apply operation ran against it.
  "no-op": { gerund: "applying", done: "unchanged" },
  // getApplyStatusMeta's ephemeral-* status cases (opening/renewed/closing) return their own
  // fixed labels without consulting this table - but the terminal "applied" status *does* land
  // here, via TerraformJsonEventParser's ephemeralCompleteStatus, which maps a completed close
  // to plain "applied" ("closed-and-gone, nothing more to show, treat as done"). "done" - not
  // "opened" - since by the time that fires the resource has been through its full open-then-
  // close cycle; "opened" alone would wrongly imply it's still active.
  ephemeral: { gerund: "opening", done: "done" },
};

// Only actions whose "done" badge shouldn't read as the default green "applied" need an
// entry here - e.g. a destroy succeeding is still notable and shouldn't look identical to a
// create succeeding.
const appliedClassNameByAction: Partial<Record<ActionName, string>> = {
  delete: "destroyed",
  replace: "replaced",
  import: "imported",
  "no-op": "unchanged",
};

// Statuses that represent an action actively happening right now, as opposed to a resource that's
// merely queued (pending/planned) or has reached a terminal state (applied/errored) - these get a
// spinning indicator on their badge so a running job visibly shows what's in flight.
const IN_PROGRESS_STATUSES = new Set<ApplyChange["status"]>([
  "refreshing",
  "reading",
  "applying",
  "provisioning",
  "importing",
  "moving",
  "ephemeral-opening",
  "ephemeral-closing",
]);

const getApplyStatusMeta = (
  action: ActionName,
  status: ApplyChange["status"],
): { label: string; className: string } => {
  switch (status) {
    case "pending":
      return { label: "pending", className: "pending" };
    case "planned":
      return { label: "planned", className: "planned" };
    case "refreshing":
      return { label: "refreshing", className: "refreshing" };
    case "reading":
      return { label: "reading", className: "reading" };
    case "applying":
      return { label: applyStatusVerbs[action].gerund, className: "applying" };
    case "provisioning":
      return { label: "provisioning", className: "provisioning" };
    case "applied":
      return {
        label: applyStatusVerbs[action].done,
        className: appliedClassNameByAction[action] ?? "applied",
      };
    case "importing":
      return { label: "importing", className: "importing" };
    case "moving":
      return { label: "moving", className: "moving" };
    case "errored":
      return { label: `${actionMeta[action].displayLabel} failed`, className: "errored" };
    case "ephemeral-opening":
      return { label: "opening", className: "ephemeral" };
    case "ephemeral-renewed":
      return { label: "renewed", className: "ephemeral" };
    case "ephemeral-closing":
      return { label: "closing", className: "ephemeral" };
    case "ephemeral-errored":
      return { label: "ephemeral operation failed", className: "errored" };
  }
};

const providerIconMap = {
  aws: FaAws,
  google: FaGoogle,
  "google-beta": FaGoogle,
  docker: FaDocker,
  github: FaGithub,
  gitlab: FaGitlab,
  slack: FaSlack,
  discord: FaDiscord,
  azurerm: FaMicrosoft,
  azuread: FaMicrosoft,
  kubernetes: SiKubernetes,
  helm: SiHelm,
  cloudflare: SiCloudflare,
  datadog: SiDatadog,
  digitalocean: SiDigitalocean,
  vault: SiVault,
  consul: SiConsul,
  postgresql: SiPostgresql,
  mysql: SiMysql,
  mongodbatlas: SiMongodb,
  elasticstack: SiElastic,
  newrelic: SiNewrelic,
  pagerduty: SiPagerduty,
  grafana: SiGrafana,
  snowflake: SiSnowflake,
  okta: SiOkta,
  vsphere: SiVmware,
  random: SiTerraform,
  "null": SiTerraform,
  time: SiTerraform,
  tls: SiTerraform,
  local: SiTerraform,
  archive: SiTerraform,
  external: SiTerraform,
  http: SiTerraform,
};

const isRecord = (value: unknown): value is Record<string, unknown> => {
  if (value === null) {
    return false;
  }

  if (Array.isArray(value)) {
    return false;
  }

  if (typeof value !== "object") {
    return false;
  }

  return true;
};

const isPrimitiveValue = (value: unknown) => {
  if (value === null) {
    return true;
  }

  if (value === undefined) {
    return true;
  }

  const valueType = typeof value;
  if (valueType === "string" || valueType === "number" || valueType === "boolean") {
    return true;
  }

  return false;
};

const isCollectionValue = (value: unknown) => {
  if (Array.isArray(value)) {
    return true;
  }

  return isRecord(value);
};

// Providers frequently store structured data (IAM policies, S3 bucket policies, jsonencode()'d
// attributes, ...) as an opaque JSON string rather than a native object/array, so the plan JSON
// has no way to tell us it's structured. Sniff the string itself: only object/array-shaped JSON
// counts, so a numeric or boolean string doesn't get pulled into a group for no reason.
const tryParseJsonContainer = (value: string): Record<string, unknown> | unknown[] | undefined => {
  const trimmed = value.trim();

  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
    return undefined;
  }

  try {
    const parsed = JSON.parse(trimmed);

    if (Array.isArray(parsed) || isRecord(parsed)) {
      return parsed;
    }
  } catch {
    // Not actually JSON - leave it to render as a plain string.
  }

  return undefined;
};

const areValuesEqual = (left: unknown, right: unknown) => {
  if (left === right) {
    return true;
  }

  try {
    return JSON.stringify(left) === JSON.stringify(right);
  } catch {
    return false;
  }
};

const getPluralSuffix = (count: number) => {
  if (count === 1) {
    return "";
  }

  return "s";
};

const getValuePreview = (value: unknown, quoteStrings: boolean = true) => {
  if (value === undefined) {
    return "not set";
  }

  if (value === null) {
    return "null";
  }

  if (typeof value === "string") {
    // No manual truncation here - the box this renders into already has CSS
    // overflow:hidden/text-overflow:ellipsis, which truncates purely visually (and adapts to the
    // box's actual width) without discarding any of the real value from the DOM, so selecting/
    // copying the text directly still gets the full value even when the display is clipped.
    return quoteStrings ? `"${value}"` : value;
  }

  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }

  if (Array.isArray(value)) {
    if (value.length === 0) {
      return "[]";
    }

    if (value.every((item) => isPrimitiveValue(item)) && JSON.stringify(value).length <= 48) {
      return JSON.stringify(value);
    }

    return `${value.length} item${getPluralSuffix(value.length)}`;
  }

  if (isRecord(value)) {
    const keys = Object.keys(value);
    if (keys.length === 0) {
      return "{}";
    }

    return `${keys.length} attribute${getPluralSuffix(keys.length)}`;
  }

  return String(value);
};

const renderValueToken = (
  value: unknown,
  kind: Exclude<DiffRow["kind"], "group">,
  options?: { sensitive?: boolean; unknown?: boolean; quoteStrings?: boolean }
) => {
  let label = getValuePreview(value, options?.quoteStrings ?? true);

  if (options?.sensitive) {
    label = "sensitive value";
  }

  if (options?.unknown) {
    label = "known after apply";
  }

  // The box this renders into visually truncates long values (CSS ellipsis) - the title gives
  // the full value on hover rather than only via the separate copy button.
  return (
    <span className={`structured-plan-valueToken structured-plan-valueToken--${kind}`} title={label}>
      {label}
    </span>
  );
};

const countVisibleLeaves = (rows: DiffRow[]): number => {
  return rows.reduce((total, row) => {
    if (!row.children?.length) {
      return total + 1;
    }

    return total + countVisibleLeaves(row.children);
  }, 0);
};

const getCollectionItemLabel = (before: unknown, after: unknown, index: number) => {
  const baseLabel = `[${index}]`;
  let candidate: Record<string, unknown> | null = null;

  if (isRecord(after)) {
    candidate = after;
  } else if (isRecord(before)) {
    candidate = before;
  }

  if (!candidate) {
    return baseLabel;
  }

  const identityKeys = ["name", "id", "key", "address", "resourceName", "resourceType"];

  for (const identityKey of identityKeys) {
    const rawValue = candidate[identityKey];

    if (typeof rawValue !== "string") {
      continue;
    }

    if (rawValue.trim().length === 0) {
      continue;
    }

    return `${baseLabel} ${rawValue}`;
  }

  return baseLabel;
};

const getCollectionIdentity = (value: unknown, index: number) => {
  if (!isRecord(value)) {
    return null;
  }

  const identityKeys = ["name", "id", "key", "address", "resourceName", "resourceType"];

  for (const identityKey of identityKeys) {
    const rawValue = value[identityKey];
    if (typeof rawValue !== "string") {
      continue;
    }

    if (rawValue.trim().length === 0) {
      continue;
    }

    return `${identityKey}:${rawValue}`;
  }

  return `index:${index}`;
};

const canMatchCollectionByIdentity = (values: unknown[]) => {
  const identities = values
    .map((value, index) => getCollectionIdentity(value, index))
    .filter((identity): identity is string => identity !== null);

  if (identities.length !== values.length) {
    return false;
  }

  return new Set(identities).size === identities.length;
};

const buildCollectionEntries = ({
  values,
  unknownValues = [],
  beforeSensitiveValues = [],
  afterSensitiveValues = [],
  changedSensitiveValues = [],
}: BuildCollectionEntriesArgs) => {
  const entries = new Map<string, CollectionEntry>();

  values.forEach((value, index) => {
    const identity = getCollectionIdentity(value, index) || `index:${index}`;
    entries.set(identity, {
      identity,
      value,
      unknown: unknownValues[index],
      beforeSensitive: beforeSensitiveValues[index],
      afterSensitive: afterSensitiveValues[index],
      changedSensitive: changedSensitiveValues[index],
    });
  });

  return entries;
};

const shouldRenderSensitiveDiff = (
  before: unknown,
  after: unknown,
  beforeSensitive: unknown,
  afterSensitive: unknown,
  changedSensitive: unknown
) => {
  const hasSensitiveMetadata = beforeSensitive === true || afterSensitive === true;

  if (changedSensitive === true) {
    return true;
  }

  // Older structured-plan payloads only include *_sensitive metadata, so fall
  // back to the sanitized values already in the browser for those responses.
  if (changedSensitive == null && hasSensitiveMetadata && !areValuesEqual(before, after)) {
    return true;
  }

  return false;
};

const buildDiffRows = (
  before: unknown,
  after: unknown,
  afterUnknown: unknown,
  beforeSensitive: unknown,
  afterSensitive: unknown,
  changedSensitive: unknown,
  label = "resource",
  includeUnchanged = false
): DiffResult => {
  const isSensitive = shouldRenderSensitiveDiff(before, after, beforeSensitive, afterSensitive, changedSensitive);
  const isUnknown = afterUnknown === true;

  if (isSensitive) {
    return {
      rows: [
        {
          key: label,
          label,
          kind: "sensitive",
          before,
          after,
        },
      ],
      hiddenCount: 0,
    };
  }

  if (isUnknown) {
    return {
      rows: [
        {
          key: label,
          label,
          kind: "unknown",
          before,
          after,
        },
      ],
      hiddenCount: 0,
    };
  }

  // If this leaf is a JSON-encoded string on either side (and the other side, if present, is
  // also JSON-shaped), decode both and fall through to the array/record handling below instead of
  // the plain-string leaf path, so it gets the same recursive attribute-by-attribute diff a native
  // nested object would - rather than one opaque, truncated string.
  const jsonContainerBefore = typeof before === "string" ? tryParseJsonContainer(before) : undefined;
  const jsonContainerAfter = typeof after === "string" ? tryParseJsonContainer(after) : undefined;
  const isBeforeJsonCompatible = before === undefined || jsonContainerBefore !== undefined;
  const isAfterJsonCompatible = after === undefined || jsonContainerAfter !== undefined;
  const wasJsonEncoded =
    (jsonContainerBefore !== undefined || jsonContainerAfter !== undefined) &&
    isBeforeJsonCompatible &&
    isAfterJsonCompatible;

  if (wasJsonEncoded) {
    before = jsonContainerBefore;
    after = jsonContainerAfter;
  }

  if (Array.isArray(before) || Array.isArray(after)) {
    const beforeArray = Array.isArray(before) ? before : [];
    const afterArray = Array.isArray(after) ? after : [];
    const unknownArray = Array.isArray(afterUnknown) ? afterUnknown : [];
    const beforeSensitiveArray = Array.isArray(beforeSensitive) ? beforeSensitive : [];
    const afterSensitiveArray = Array.isArray(afterSensitive) ? afterSensitive : [];
    const changedSensitiveArray = Array.isArray(changedSensitive) ? changedSensitive : [];

    const rows: DiffRow[] = [];
    let hiddenCount = 0;

    const canUseIdentityMatching =
      beforeArray.every((item) => isRecord(item)) &&
      afterArray.every((item) => isRecord(item)) &&
      canMatchCollectionByIdentity(beforeArray) &&
      canMatchCollectionByIdentity(afterArray);

    if (canUseIdentityMatching) {
      const beforeEntries = buildCollectionEntries({
        values: beforeArray,
        beforeSensitiveValues: beforeSensitiveArray,
      });
      const afterEntries = buildCollectionEntries({
        values: afterArray,
        unknownValues: unknownArray,
        afterSensitiveValues: afterSensitiveArray,
      });
      // `changedSensitive` arrives as a parallel array, so rebuild entry
      // identities from whichever side still has the collection items.
      const changedSensitiveEntries = buildCollectionEntries({
        values: afterArray.length > 0 ? afterArray : beforeArray,
        changedSensitiveValues: changedSensitiveArray,
      });
      const orderedIdentities = Array.from(new Set([...beforeEntries.keys(), ...afterEntries.keys()]));

      orderedIdentities.forEach((identity, index) => {
        const beforeEntry = beforeEntries.get(identity);
        const afterEntry = afterEntries.get(identity);
        const itemLabel = `[${index}]`;
        const itemDisplayLabel = getCollectionItemLabel(beforeEntry?.value, afterEntry?.value, index);
        const childDiff = buildDiffRows(
          beforeEntry?.value,
          afterEntry?.value,
          afterEntry?.unknown,
          beforeEntry?.beforeSensitive,
          afterEntry?.afterSensitive,
          changedSensitiveEntries.get(identity)?.changedSensitive,
          itemLabel,
          includeUnchanged
        );

        hiddenCount += childDiff.hiddenCount;

        if (childDiff.rows.length === 0) {
          return;
        }

        if (
          childDiff.rows.length === 1 &&
          !childDiff.rows[0].children?.length &&
          childDiff.rows[0].label === itemLabel
        ) {
          rows.push(childDiff.rows[0]);
          return;
        }

        rows.push({
          key: identity,
          label: itemDisplayLabel,
          kind: "group",
          children: childDiff.rows,
          hiddenCount: childDiff.hiddenCount,
          jsonEncoded: childDiff.wasJsonEncoded,
        });
      });
    } else {
      const maxLength = Math.max(beforeArray.length, afterArray.length);

      for (let index = 0; index < maxLength; index += 1) {
        const itemLabel = `[${index}]`;
        const itemDisplayLabel = getCollectionItemLabel(beforeArray[index], afterArray[index], index);
        const childDiff = buildDiffRows(
          beforeArray[index],
          afterArray[index],
          unknownArray[index],
          beforeSensitiveArray[index],
          afterSensitiveArray[index],
          changedSensitiveArray[index],
          itemLabel,
          includeUnchanged
        );

        hiddenCount += childDiff.hiddenCount;

        if (childDiff.rows.length === 0) {
          continue;
        }

        if (
          childDiff.rows.length === 1 &&
          !childDiff.rows[0].children?.length &&
          childDiff.rows[0].label === itemLabel
        ) {
          rows.push(childDiff.rows[0]);
          continue;
        }

        rows.push({
          key: itemLabel,
          label: itemDisplayLabel,
          kind: "group",
          children: childDiff.rows,
          hiddenCount: childDiff.hiddenCount,
          jsonEncoded: childDiff.wasJsonEncoded,
        });
      }
    }

    return {
      rows,
      hiddenCount,
      wasJsonEncoded,
    };
  }

  // Not "either side is primitive" - undefined/null are primitive too, and a wholly-created or
  // wholly-deleted resource has `before`/`after` outright undefined at the top level. Basing the
  // leaf decision on that would collapse a brand-new object/array attribute straight to "N
  // attributes" instead of recursing into it, which is exactly backwards for a create/delete. The
  // recursion below already treats a missing side as `{}`/`[]`, so every key still shows up
  // correctly marked added/removed.
  const treatAsLeaf = !isCollectionValue(before) && !isCollectionValue(after);
  if (treatAsLeaf) {
    if (areValuesEqual(before, after)) {
      if (!includeUnchanged) {
        return {
          rows: [],
          hiddenCount: 1,
        };
      }

      return {
        rows: [
          {
            key: label,
            label,
            kind: "unchanged",
            before,
            after,
          },
        ],
        hiddenCount: 1,
      };
    }

    let kind: DiffRow["kind"] = "changed";
    if (before === undefined) {
      kind = "added";
    } else if (after === undefined) {
      kind = "removed";
    }

    return {
      rows: [
        {
          key: label,
          label,
          kind,
          before,
          after,
        },
      ],
      hiddenCount: 0,
    };
  }

  const beforeRecord = isRecord(before) ? before : {};
  const afterRecord = isRecord(after) ? after : {};
  const unknownRecord = isRecord(afterUnknown) ? afterUnknown : {};
  const beforeSensitiveRecord = isRecord(beforeSensitive) ? beforeSensitive : {};
  const afterSensitiveRecord = isRecord(afterSensitive) ? afterSensitive : {};
  const changedSensitiveRecord = isRecord(changedSensitive) ? changedSensitive : {};

  const keys = Array.from(new Set([...Object.keys(beforeRecord), ...Object.keys(afterRecord)])).sort();
  const rows: DiffRow[] = [];
  let hiddenCount = 0;

  keys.forEach((key) => {
    const childDiff = buildDiffRows(
      beforeRecord[key],
      afterRecord[key],
      unknownRecord[key],
      beforeSensitiveRecord[key],
      afterSensitiveRecord[key],
      changedSensitiveRecord[key],
      key,
      includeUnchanged
    );

    hiddenCount += childDiff.hiddenCount;

    if (childDiff.rows.length === 0) {
      return;
    }

    if (
      childDiff.rows.length === 1 &&
      !childDiff.rows[0].children?.length &&
      childDiff.rows[0].label === key &&
      !isCollectionValue(beforeRecord[key]) &&
      !isCollectionValue(afterRecord[key])
    ) {
      rows.push(childDiff.rows[0]);
      return;
    }

    rows.push({
      key,
      label: key,
      kind: "group",
      children: childDiff.rows,
      hiddenCount: childDiff.hiddenCount,
      jsonEncoded: childDiff.wasJsonEncoded,
    });
  });

  return {
    rows,
    hiddenCount,
    wasJsonEncoded,
  };
};

const getDiffMarker = (kind: Exclude<DiffRow["kind"], "group">) => {
  if (kind === "added") {
    return "+";
  }

  if (kind === "removed") {
    return "-";
  }

  if (kind === "unknown") {
    return "?";
  }

  if (kind === "sensitive") {
    return "*";
  }

  if (kind === "unchanged") {
    return "=";
  }

  return "~";
};

const renderDiffRows = (rows: DiffRow[], parentKey = "root"): ReactNode => {
  return rows.map((row, index) => {
    const rowKey = `${parentKey}-${row.key}-${index}`;

    if (row.children?.length) {
      return (
        <div key={rowKey} className="structured-plan-diffGroup">
          <div className="structured-plan-diffGroupLabel">
            {row.label}
            {row.jsonEncoded ? <span className="structured-plan-jsonWrap">jsonencode({"{"}</span> : null}
          </div>
          <div className="structured-plan-diffGroupChildren">{renderDiffRows(row.children, rowKey)}</div>
          {row.hiddenCount ? (
            <div className="structured-plan-hiddenCount">
              {row.hiddenCount} unchanged attribute{getPluralSuffix(row.hiddenCount)} hidden
            </div>
          ) : null}
          {row.jsonEncoded ? <div className="structured-plan-jsonWrap structured-plan-jsonWrapClose">{"}"})</div> : null}
        </div>
      );
    }

    const leafKind = row.kind as Exclude<DiffRow["kind"], "group">;
    const marker = getDiffMarker(leafKind);
    const showUnknown = leafKind === "unknown";
    const showSensitive = leafKind === "sensitive";

    let valueContent: ReactNode = renderValueToken(row.after, leafKind);

    if (leafKind === "removed") {
      valueContent = renderValueToken(row.before, leafKind);
    }

    if (leafKind === "changed" || leafKind === "unknown" || leafKind === "sensitive") {
      valueContent = (
        <div className="structured-plan-diffValueFlow">
          {renderValueToken(row.before, leafKind, { sensitive: showSensitive })}
          <span className="structured-plan-diffArrow">=&gt;</span>
          {renderValueToken(row.after, leafKind, {
            sensitive: showSensitive,
            unknown: showUnknown,
          })}
        </div>
      );
    }

    return (
      <div key={rowKey} className="structured-plan-diffRow">
        <div className="structured-plan-diffLabel">
          <span className={`structured-plan-diffMarker structured-plan-diffMarker--${row.kind}`}>{marker}</span>
          <span>{row.label}</span>
        </div>
        <div className="structured-plan-diffValue">{valueContent}</div>
      </div>
    );
  });
};

type OutputValueNode = {
  key: string;
  label: string;
  value?: unknown;
  children?: OutputValueNode[];
  jsonEncoded?: boolean;
};

// Outputs have no before/after to diff - just a value to lay out - but they hit the same
// truncated-single-line problem as resource attributes once that value is a nested object/array
// or a JSON-encoded string, so this mirrors buildDiffRows' recursion (and its jsonencode()
// detection) without the diff bookkeeping.
const buildOutputValueNode = (value: unknown, label: string, key: string): OutputValueNode => {
  if (typeof value === "string") {
    const parsed = tryParseJsonContainer(value);
    if (parsed !== undefined) {
      return { ...buildOutputValueNode(parsed, label, key), jsonEncoded: true };
    }
  }

  if (Array.isArray(value) && value.length > 0 && !(value.every(isPrimitiveValue) && JSON.stringify(value).length <= 48)) {
    return {
      key,
      label,
      children: value.map((item, index) =>
        buildOutputValueNode(item, getCollectionItemLabel(undefined, item, index), `${key}-${index}`)
      ),
    };
  }

  if (isRecord(value) && Object.keys(value).length > 0) {
    return {
      key,
      label,
      children: Object.keys(value).map((childKey) =>
        buildOutputValueNode(value[childKey], childKey, `${key}.${childKey}`)
      ),
    };
  }

  return { key, label, value };
};

// Renders the *contents* of an expanded named output - only the named output itself (see the
// Outputs section below) gets a collapse toggle and the boxed/copyable leaf treatment. Everything
// under it, however deeply nested, renders as a plain compact row: collapsing every array item and
// object key individually would take several clicks just to read one policy document, and boxing
// every single leaf in its own bordered/copy-button token is what made a decoded JSON value look
// like a wall of input fields in the first place.
const renderOutputValueNode = (node: OutputValueNode): ReactNode => {
  if (node.children?.length) {
    return (
      <div className="structured-plan-diffGroup" key={node.key}>
        <div className="structured-plan-diffGroupLabel">
          {node.label}
          {node.jsonEncoded ? <span className="structured-plan-jsonWrap">jsonencode({"{"}</span> : null}
        </div>
        <div className="structured-plan-diffGroupChildren">{node.children.map((child) => renderOutputValueNode(child))}</div>
        {node.jsonEncoded ? <div className="structured-plan-jsonWrap structured-plan-jsonWrapClose">{"}"})</div> : null}
      </div>
    );
  }

  const preview = getValuePreview(node.value, false);

  return (
    <div className="structured-plan-outputLeafRow" key={node.key}>
      <span className="structured-plan-outputLeafLabel">{node.label}</span>
      <span className="structured-plan-outputLeafValue" title={preview}>
        {preview}
      </span>
    </div>
  );
};

const normalizeActionName = (value: string): ActionName => {
  if (
    value === "create" ||
    value === "update" ||
    value === "replace" ||
    value === "delete" ||
    value === "read" ||
    value === "import" ||
    value === "unknown" ||
    value === "no-op" ||
    value === "ephemeral"
  ) {
    return value;
  }

  return "unknown";
};

const getResourceAddress = (change: PlanChange) => {
  if (change.address) {
    return change.address;
  }

  if (change.resourceType && change.resourceName) {
    return `${change.resourceType}.${change.resourceName}`;
  }

  if (change.resourceType) {
    return change.resourceType;
  }

  if (change.resourceName) {
    return change.resourceName;
  }

  return "resource";
};

const getProviderName = (change: PlanChange) => {
  if (change.resourceType) {
    const segments = change.resourceType.split("_");
    if (segments[0] && segments[0].trim().length > 0) {
      return segments[0];
    }
  }

  const resourceLabel = getResourceAddress(change);
  const normalizedLabel = resourceLabel.replace(/^data\./, "");
  const segments = normalizedLabel.split(".");

  for (const segment of segments) {
    if (!segment.includes("_")) {
      continue;
    }

    const resourceSegments = segment.split("_");
    if (resourceSegments[0] && resourceSegments[0].trim().length > 0) {
      return resourceSegments[0];
    }
  }

  return "terraform";
};

const isDataSourceChange = (change: PlanChange, action: ActionName) => {
  if (action === "read") {
    return true;
  }

  const resourceLabel = getResourceAddress(change);
  if (resourceLabel.startsWith("data.")) {
    return true;
  }

  if (resourceLabel.includes(".data.")) {
    return true;
  }

  return false;
};

const buildResourceDiff = (change: PlanChange, action: ActionName, includeUnchanged = false) => {
  if (action === "create") {
    return buildDiffRows(
      undefined,
      change.after,
      change.afterUnknown,
      undefined,
      change.afterSensitive,
      change.changedSensitive,
      "resource",
      includeUnchanged
    );
  }

  if (action === "delete") {
    return buildDiffRows(
      change.before,
      undefined,
      undefined,
      change.beforeSensitive,
      undefined,
      change.changedSensitive,
      "resource",
      includeUnchanged
    );
  }

  return buildDiffRows(
    change.before,
    change.after,
    change.afterUnknown,
    change.beforeSensitive,
    change.afterSensitive,
    change.changedSensitive,
    "resource",
    includeUnchanged
  );
};

const buildSummary = (rows: PreparedChangeRow[]): SummaryCounts => {
  return rows.reduce<SummaryCounts>(
    (summary, row) => {
      if (row.action === "replace") {
        summary.create += 1;
        summary.delete += 1;
        summary.replace += 1;
        return summary;
      }

      if (row.action === "create") {
        summary.create += 1;
        return summary;
      }

      if (row.action === "update") {
        summary.update += 1;
        return summary;
      }

      if (row.action === "delete") {
        summary.delete += 1;
        return summary;
      }

      if (row.action === "read") {
        summary.read += 1;
        return summary;
      }

      if (row.action === "import") {
        summary.import += 1;
        return summary;
      }

      summary.unknown += 1;
      return summary;
    },
    {
      create: 0,
      update: 0,
      delete: 0,
      read: 0,
      import: 0,
      unknown: 0,
      replace: 0,
    }
  );
};

const buildSummarySegments = (summary: SummaryCounts): SummarySegment[] => {
  const segments: SummarySegment[] = [];

  if (summary.create > 0) {
    segments.push({
      key: "create",
      label: `${summary.create} to create`,
      count: summary.create,
      symbol: "+",
    });
  }

  if (summary.update > 0) {
    segments.push({
      key: "update",
      label: `${summary.update} to change`,
      count: summary.update,
      symbol: "~",
    });
  }

  if (summary.delete > 0) {
    segments.push({
      key: "delete",
      label: `${summary.delete} to destroy`,
      count: summary.delete,
      symbol: "-",
    });
  }

  if (summary.import > 0) {
    segments.push({
      key: "import",
      label: `${summary.import} to import`,
      count: summary.import,
      symbol: "i",
    });
  }

  return segments;
};

const formatResourceSummary = (summary: SummaryCounts) => {
  const parts: string[] = [];

  if (summary.create > 0) {
    parts.push(`${summary.create} to create`);
  }

  if (summary.update > 0) {
    parts.push(`${summary.update} to change`);
  }

  if (summary.delete > 0) {
    parts.push(`${summary.delete} to destroy`);
  }

  if (summary.import > 0) {
    parts.push(`${summary.import} to import`);
  }

  if (parts.length === 0) {
    return "No managed resource changes";
  }

  return parts.join(", ");
};

const extractTerraformVersion = (outputLog?: string) => {
  if (!outputLog) {
    return undefined;
  }

  const sanitizedOutput = stripAnsi(outputLog);
  const match = sanitizedOutput.match(/Terraform v([^\s]+)/);
  if (!match?.[1]) {
    return undefined;
  }

  return `Terraform ${match[1]}`;
};

const matchesAddressFilter = (row: PreparedChangeRow, filterValue: string) => {
  const normalizedFilterValue = filterValue.trim().toLowerCase();

  if (normalizedFilterValue.length === 0) {
    return true;
  }

  const searchParts = [row.resourceLabel, row.change.moduleAddress, row.change.resourceType, row.providerName];

  return searchParts.some((part) => {
    if (!part) {
      return false;
    }

    return part.toLowerCase().includes(normalizedFilterValue);
  });
};

export const StructuredPlanOutput = ({
  changes,
  outputLog,
  applyMode = false,
  outputs,
  jobDiagnostics,
  isStepRunning = false,
}: Props) => {
  const [expandedRowKeys, setExpandedRowKeys] = useState<string[]>([]);
  const [expandedAttributeKeys, setExpandedAttributeKeys] = useState<string[]>([]);
  const [addressFilter, setAddressFilter] = useState("");
  const [operationFilters, setOperationFilters] = useState<Set<ActionName>>(new Set());
  const [isOperationFilterOpen, setIsOperationFilterOpen] = useState(false);
  // Expanded by default, unlike each resource row - outputs are the thing users come here to
  // read, so starting collapsed like a per-resource diff would just add an extra click for the
  // common case.
  const [areOutputsExpanded, setAreOutputsExpanded] = useState(true);
  // Unlike the section itself, individual nested/object-valued outputs start collapsed - a plan
  // with several jsonencode()'d or object outputs would otherwise dump every one of them open at
  // once.
  const [expandedOutputNames, setExpandedOutputNames] = useState<string[]>([]);

  const preparedRows = useMemo<PreparedChangeRow[]>(() => {
    return changes.flatMap((change, index) => {
      const normalizedAction = normalizeActionName(getPlanChangeActionLabel(change.actions, change.action));

      // No-op resources (nothing to create/update/destroy) are excluded from the structured view
      // entirely - matches `terraform plan`'s own CLI output, which never lists unchanged
      // resources either. Filtering here (rather than upstream) means their live "refreshing"
      // status still flows through the underlying data fine while a run is active; they just
      // never render as a row.
      if (normalizedAction === "no-op") {
        return [];
      }

      const resourceLabel = getResourceAddress(change);
      const providerName = getProviderName(change);
      const diff = buildResourceDiff(change, normalizedAction);
      const applyStatus = "status" in change ? (change as ApplyChange).status : undefined;
      const applyDiagnostics = "diagnostics" in change ? (change as ApplyChange).diagnostics : undefined;
      const applyElapsedSeconds = "elapsedSeconds" in change ? (change as ApplyChange).elapsedSeconds : undefined;
      const applyCurrentProvisioner = "currentProvisioner" in change ? (change as ApplyChange).currentProvisioner : undefined;
      const applyProvisionerOutput = "provisionerOutput" in change ? (change as ApplyChange).provisionerOutput : undefined;
      const driftAction = change.driftAction;

      return [
        {
          key: `${resourceLabel}-${normalizedAction}-${index}`,
          panelId: `structured-plan-panel-${index}`,
          change,
          action: normalizedAction,
          resourceLabel,
          providerName,
          isDataSource: isDataSourceChange(change, normalizedAction),
          diff,
          visibleChanges: countVisibleLeaves(diff.rows),
          hiddenCount: diff.hiddenCount,
          applyStatus,
          applyDiagnostics,
          applyElapsedSeconds,
          applyCurrentProvisioner,
          applyProvisionerOutput,
          driftAction,
        },
      ];
    });
  }, [changes]);

  const applyProgress = useMemo(() => {
    if (!applyMode) {
      return null;
    }

    const appliedCount = preparedRows.filter((row) => row.applyStatus === "applied").length;
    return { appliedCount, totalCount: preparedRows.length };
  }, [applyMode, preparedRows]);

  const summary = useMemo(() => {
    return buildSummary(preparedRows);
  }, [preparedRows]);

  const summarySegments = useMemo(() => {
    return buildSummarySegments(summary);
  }, [summary]);

  const hasDataSourceChanges = useMemo(() => {
    return preparedRows.some((row) => row.isDataSource);
  }, [preparedRows]);

  const hasNonDataSourceChanges = useMemo(() => {
    return preparedRows.some((row) => !row.isDataSource);
  }, [preparedRows]);

  const showDataSourcesByDefault = useMemo(() => {
    if (!hasDataSourceChanges) {
      return true;
    }

    if (!hasNonDataSourceChanges) {
      return true;
    }

    return false;
  }, [hasDataSourceChanges, hasNonDataSourceChanges]);
  const [showDataSources, setShowDataSources] = useState(() => showDataSourcesByDefault);

  const terraformVersion = useMemo(() => {
    return extractTerraformVersion(outputLog);
  }, [outputLog]);

  useEffect(() => {
    setShowDataSources(showDataSourcesByDefault);
  }, [showDataSourcesByDefault]);

  useEffect(() => {
    setExpandedRowKeys((currentKeys) => {
      const validKeys = new Set(preparedRows.map((row) => row.key));
      return currentKeys.filter((key) => validKeys.has(key));
    });
  }, [preparedRows]);

  const filteredRows = useMemo(() => {
    return preparedRows.filter((row) => {
      if (!showDataSources && row.isDataSource) {
        return false;
      }

      if (operationFilters.size > 0 && !operationFilters.has(row.action)) {
        return false;
      }

      if (!matchesAddressFilter(row, addressFilter)) {
        return false;
      }

      return true;
    });
  }, [addressFilter, operationFilters, preparedRows, showDataSources]);

  const hasJobDiagnostics = Boolean(jobDiagnostics && jobDiagnostics.length);

  // An empty changes list normally means "we compared and nothing differs" - but it's also what
  // a failed plan looks like before any resource address was ever seen (see
  // TerraformExecutorServiceImpl.plan()'s final flush), and what a plan that's still running
  // looks like before the backend has streamed any resource changes at all. Job-level
  // diagnostics or a still-running step are both ways of saying "we don't actually know", not
  // "nothing changed" - fall through instead of claiming success.
  if (!changes?.length && !hasJobDiagnostics) {
    if (isStepRunning) {
      return (
        <div className="structured-plan-noChanges structured-plan-inProgress">
          <LoadingOutlined className="structured-plan-noChangesIcon" />
          <span>Plan is running — waiting for results…</span>
        </div>
      );
    }

    return (
      <div className="structured-plan-noChanges">
        <CheckCircleOutlined className="structured-plan-noChangesIcon" />
        <span>Your infrastructure matches the configuration — no changes needed.</span>
      </div>
    );
  }

  const isFilteredView =
    filteredRows.length !== preparedRows.length || addressFilter.trim().length > 0 || operationFilters.size > 0;

  let emptyDescription = "No resources match the current filters.";
  if (!changes?.length && hasJobDiagnostics) {
    emptyDescription = "No changes were computed — see diagnostics above.";
  } else if (!showDataSources && hasDataSourceChanges) {
    emptyDescription = "No resources match the current filters. Enable Show data sources to include read operations.";
  }

  const toggleRow = (rowKey: string) => {
    setExpandedRowKeys((currentKeys) => {
      if (currentKeys.includes(rowKey)) {
        return currentKeys.filter((currentKey) => currentKey !== rowKey);
      }

      return [...currentKeys, rowKey];
    });
  };

  const toggleAttributesExpanded = (rowKey: string) => {
    setExpandedAttributeKeys((currentKeys) => {
      if (currentKeys.includes(rowKey)) {
        return currentKeys.filter((currentKey) => currentKey !== rowKey);
      }

      return [...currentKeys, rowKey];
    });
  };

  const toggleOutputsExpanded = () => {
    setAreOutputsExpanded((current) => !current);
  };

  const toggleOutputExpanded = (outputName: string) => {
    setExpandedOutputNames((currentNames) => {
      if (currentNames.includes(outputName)) {
        return currentNames.filter((currentName) => currentName !== outputName);
      }

      return [...currentNames, outputName];
    });
  };

  const handleAddressFilterChange = (event: ChangeEvent<HTMLInputElement>) => {
    setAddressFilter(event.target.value);
  };

  const toggleOperationFilter = (action: ActionName) => {
    setOperationFilters((currentFilters) => {
      const nextFilters = new Set(currentFilters);
      if (nextFilters.has(action)) {
        nextFilters.delete(action);
      } else {
        nextFilters.add(action);
      }

      return nextFilters;
    });
  };

  const handleShowDataSourcesChange = (event: ChangeEvent<HTMLInputElement>) => {
    setShowDataSources(event.target.checked);
  };

  const handleCopyValue = async (value: string) => {
    if (!navigator.clipboard?.writeText) {
      message.error("Clipboard access is unavailable in this browser.");
      return;
    }

    try {
      await navigator.clipboard.writeText(value);
      message.success("Copied to clipboard");
    } catch {
      message.error("Failed to copy to clipboard");
    }
  };

  const handleDownloadRawLog = () => {
    if (!outputLog) {
      return;
    }

    const sanitizedOutput = stripAnsi(outputLog);
    const blob = new Blob([sanitizedOutput], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");

    anchor.href = url;
    anchor.download = "terraform-plan.log";
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  };

  return (
    <div className="structured-plan">
      <div className="structured-plan-shell">
        <div className="structured-plan-summaryArea">
          <div className="structured-plan-summaryStrip">
            {summarySegments.length ? (
              summarySegments.map((segment) => {
                return (
                  <div
                    key={segment.key}
                    className={`structured-plan-summarySegment structured-plan-summarySegment--${segment.key}`}
                  >
                    <span className="structured-plan-summarySymbol">{segment.symbol}</span>
                    <span>{segment.label}</span>
                  </div>
                );
              })
            ) : (
              <div className="structured-plan-summaryEmpty">No managed resource changes.</div>
            )}
          </div>
          <div className="structured-plan-summaryMeta">
            {applyProgress ? (
              <span>
                {applyProgress.appliedCount} of {applyProgress.totalCount} applied
              </span>
            ) : (
              <>
                <span>Resources: {formatResourceSummary(summary)}</span>
                <span>Actions: {summary.read} to invoke</span>
              </>
            )}
          </div>
        </div>

        <div className="structured-plan-toolbar">
          <div className="structured-plan-toolbarControls">
            <input
              aria-label="Filter resources by address"
              className="structured-plan-searchInput"
              onChange={handleAddressFilterChange}
              placeholder="Filter resources by address..."
              type="text"
              value={addressFilter}
            />

            <div className="structured-plan-actionFilter">
              <button
                aria-expanded={isOperationFilterOpen}
                aria-label="Filter by operation"
                className="structured-plan-selectWrap structured-plan-actionFilterButton"
                onClick={() => setIsOperationFilterOpen((isOpen) => !isOpen)}
                type="button"
              >
                <FilterOutlined className="structured-plan-selectIcon" />
                <span>{operationFilters.size === 0 ? "All operations" : `${operationFilters.size} selected`}</span>
                <DownOutlined className="structured-plan-selectChevron" />
              </button>

              {isOperationFilterOpen ? (
                <div className="structured-plan-actionFilterPanel">
                  {(
                    [
                      // The checkbox below filters by each row's own single action strictly
                      // (row.action === "create", never "replace" too), so its count must show
                      // exactly what checking it will reveal - summary.create/delete count a
                      // replace toward *both* (matching the top summary bar's Terraform-native
                      // "N to add, N to destroy" convention), so that double-counted contribution
                      // has to be subtracted back out here or the label would promise rows this
                      // checkbox doesn't actually surface.
                      ["create", summary.create - summary.replace],
                      ["update", summary.update],
                      ["replace", summary.replace],
                      ["delete", summary.delete - summary.replace],
                      ["read", summary.read],
                      ["import", summary.import],
                    ] as [ActionName, number][]
                  ).map(([action, count]) => {
                    const label = `${actionMeta[action].filterLabel} (${count})`;

                    return (
                      <label className="structured-plan-checkbox" key={action}>
                        <input
                          checked={operationFilters.has(action)}
                          onChange={() => toggleOperationFilter(action)}
                          type="checkbox"
                        />
                        <span>{label}</span>
                      </label>
                    );
                  })}
                </div>
              ) : null}
            </div>

            <label className="structured-plan-checkbox">
              <input checked={showDataSources} onChange={handleShowDataSourcesChange} type="checkbox" />
              <span>Show data sources</span>
            </label>
          </div>

          <div className="structured-plan-toolbarMeta">
            {isFilteredView ? (
              <span>
                Showing {filteredRows.length} of {preparedRows.length} change{getPluralSuffix(preparedRows.length)}
              </span>
            ) : null}
            {terraformVersion ? <span>{terraformVersion}</span> : null}
            {outputLog ? (
              <button className="structured-plan-toolbarButton" onClick={handleDownloadRawLog} type="button">
                <DownloadOutlined />
                <span>Download raw log</span>
              </button>
            ) : null}
          </div>
        </div>

        {jobDiagnostics != null && jobDiagnostics.length > 0 ? (
          <div className="structured-plan-jobDiagnostics">
            {jobDiagnostics.map((diagnostic, index) => (
              <div key={index} className={`structured-plan-jobDiagnostic structured-plan-jobDiagnostic--${diagnostic.severity}`}>
                <span className="structured-plan-diagnostic-summary">
                  {diagnostic.summary}
                  {diagnostic.location ? (
                    <span className="structured-plan-diagnostic-location"> ({diagnostic.location})</span>
                  ) : null}
                </span>
                {diagnostic.detail ? (
                  <span className="structured-plan-diagnostic-detail">{diagnostic.detail}</span>
                ) : null}
              </div>
            ))}
          </div>
        ) : null}

        <div className="structured-plan-rows">
          {filteredRows.length ? (
            filteredRows.map((row) => {
              const isExpanded = expandedRowKeys.includes(row.key);
              // An import has no diff to hide by default - every attribute is just the
              // resource's current real value, so showing them up front (and letting the
              // toggle collapse them away) is more useful than an empty "no changes" state.
              const attributesExpandedByDefault = row.action === "import";
              const isAttributeToggleActive = expandedAttributeKeys.includes(row.key);
              const isAttributesExpanded = attributesExpandedByDefault ? !isAttributeToggleActive : isAttributeToggleActive;
              const visibleDiffRows = isAttributesExpanded
                ? buildResourceDiff(row.change, row.action, true).rows
                : row.diff.rows;
              const rowActionMeta = actionMeta[row.action];
              // Outside apply mode, a plan row's status is almost always "planned" - showing that
              // as a badge next to every single row would just repeat what the action icon
              // already says. "errored" is the one plan-phase status worth surfacing here: it's
              // the only indicator (besides the diagnostics list below) that a resource failed
              // before Terraform ever computed a diff for it, so it never got one of the normal
              // create/update/delete icons.
              const rowApplyStatusMeta =
                row.applyStatus && (applyMode || row.applyStatus === "errored")
                  ? getApplyStatusMeta(row.action, row.applyStatus)
                  : null;
              const normalizedProviderName = row.providerName.toLowerCase() as keyof typeof providerIconMap;
              const ProviderIcon = providerIconMap[normalizedProviderName];

              return (
                <div key={row.key} className="structured-plan-row">
                  <div className="structured-plan-rowHeader">
                    <button
                      aria-controls={row.panelId}
                      aria-expanded={isExpanded}
                      className="structured-plan-rowToggle"
                      onClick={() => toggleRow(row.key)}
                      type="button"
                    >
                      <span
                        className={`structured-plan-chevron${isExpanded ? " structured-plan-chevron--expanded" : ""}`}
                      >
                        <RightOutlined />
                      </span>
                      <span
                        aria-hidden="true"
                        className={`structured-plan-actionIcon structured-plan-actionIcon--${rowActionMeta.className}`}
                        title={rowActionMeta.displayLabel}
                      >
                        {rowActionMeta.symbol}
                      </span>
                      <span className="structured-plan-providerBadge" title={row.providerName}>
                        {ProviderIcon ? (
                          <ProviderIcon className="structured-plan-providerBadgeIcon" />
                        ) : (
                          <DeploymentUnitOutlined className="structured-plan-providerBadgeIcon structured-plan-providerBadgeIcon--generic" />
                        )}
                      </span>
                      <span className="structured-plan-address" title={row.resourceLabel}>
                        {row.resourceLabel}
                      </span>
                      {row.driftAction ? (
                        <span
                          className="structured-plan-drift"
                          title={`Detected outside Terraform/OpenTofu: drifted to ${row.driftAction}`}
                        >
                          drift: {row.driftAction}
                        </span>
                      ) : null}
                      {row.action === "replace" && !rowApplyStatusMeta ? (
                        // The -/+ action icon on the left already says "replace", but it's the
                        // one action that's genuinely harder to parse at a glance than a plain
                        // +/-/~ - spell it out here the same way the apply-time "Replaced" badge
                        // does (dual dot, no new hue), rather than leaving it to the icon alone.
                        // Only shown while there's no apply-status badge yet (i.e. during plan) -
                        // once applyMode's own "Replaced" badge appears, this would be redundant.
                        <span className="structured-plan-applyStatus structured-plan-applyStatus--replaced">
                          <span className="structured-plan-applyStatusDots" aria-hidden="true">
                            <span className="structured-plan-applyStatusDot structured-plan-applyStatusDot--destroy" />
                            <span className="structured-plan-applyStatusDot structured-plan-applyStatusDot--create" />
                          </span>
                          will replace
                        </span>
                      ) : null}
                      {rowApplyStatusMeta ? (
                        <span
                          className={`structured-plan-applyStatus structured-plan-applyStatus--${rowApplyStatusMeta.className}`}
                          title={
                            row.applyStatus === "errored"
                              ? row.applyDiagnostics?.find((diagnostic) => diagnostic.severity === "error")?.summary
                              : undefined
                          }
                        >
                          {row.applyStatus && IN_PROGRESS_STATUSES.has(row.applyStatus) ? (
                            <LoadingOutlined className="structured-plan-applyStatusSpinner" />
                          ) : null}
                          {rowApplyStatusMeta.className === "replaced" ? (
                            <span className="structured-plan-applyStatusDots" aria-hidden="true">
                              <span className="structured-plan-applyStatusDot structured-plan-applyStatusDot--destroy" />
                              <span className="structured-plan-applyStatusDot structured-plan-applyStatusDot--create" />
                            </span>
                          ) : null}
                          {rowApplyStatusMeta.label}
                        </span>
                      ) : null}
                    </button>

                    <button
                      aria-label="Copy resource address"
                      className="structured-plan-copyButton"
                      onClick={(event) => {
                        event.stopPropagation();
                        void handleCopyValue(row.resourceLabel);
                      }}
                      title="Copy resource address"
                      type="button"
                    >
                      <CopyOutlined />
                    </button>
                  </div>

                  {isExpanded ? (
                    <div className="structured-plan-rowBody" id={row.panelId}>
                      <div className="structured-plan-rowBodyInner">
                        <div className="structured-plan-rowMeta">
                          <div className="structured-plan-rowMetaTags">
                            <span
                              className={`structured-plan-actionPill structured-plan-actionPill--${rowActionMeta.className}`}
                            >
                              {rowActionMeta.displayLabel}
                            </span>
                            {row.change.resourceType ? (
                              <span className="structured-plan-metaPill">{row.change.resourceType}</span>
                            ) : null}
                            {row.change.moduleAddress ? (
                              <span className="structured-plan-metaPill">{row.change.moduleAddress}</span>
                            ) : null}
                            {row.isDataSource ? <span className="structured-plan-metaPill">data source</span> : null}
                            {row.change.importing?.id ? (
                              <span className="structured-plan-metaPill structured-plan-metaPill--import">
                                imported: {row.change.importing.id}
                              </span>
                            ) : null}
                          </div>

                          <div className="structured-plan-rowCounts">
                            <span>
                              {row.visibleChanges} visible change{getPluralSuffix(row.visibleChanges)}
                            </span>
                          </div>
                        </div>

                        {visibleDiffRows.length ? (
                          <div className="structured-plan-diffList">{renderDiffRows(visibleDiffRows)}</div>
                        ) : (
                          <div className="structured-plan-noAttributeChanges">No visible attribute changes.</div>
                        )}

                        {row.hiddenCount ? (
                          <button
                            aria-expanded={isAttributesExpanded}
                            className="structured-plan-hiddenCountToggle"
                            onClick={() => toggleAttributesExpanded(row.key)}
                            type="button"
                          >
                            <span
                              aria-hidden="true"
                              className={`structured-plan-chevron structured-plan-hiddenCountChevron${
                                isAttributesExpanded ? " structured-plan-chevron--expanded" : ""
                              }`}
                            >
                              <RightOutlined />
                            </span>
                            <span>
                              {isAttributesExpanded
                                ? "Hide unchanged attributes"
                                : `Show ${row.hiddenCount} unchanged attribute${getPluralSuffix(row.hiddenCount)}`}
                            </span>
                          </button>
                        ) : null}

                        {row.applyDiagnostics != null && row.applyDiagnostics.length > 0 ? (
                          <div className="structured-plan-diagnostics">
                            {row.applyDiagnostics.map((diagnostic, diagnosticIndex) => (
                              <div
                                key={diagnosticIndex}
                                className={`structured-plan-diagnostic structured-plan-diagnostic--${diagnostic.severity}`}
                              >
                                <span className="structured-plan-diagnostic-summary">
                                  {diagnostic.summary}
                                  {diagnostic.location ? (
                                    <span className="structured-plan-diagnostic-location"> ({diagnostic.location})</span>
                                  ) : null}
                                </span>
                                {diagnostic.detail ? (
                                  <span className="structured-plan-diagnostic-detail">{diagnostic.detail}</span>
                                ) : null}
                              </div>
                            ))}
                          </div>
                        ) : null}

                        {row.applyProvisionerOutput != null && row.applyProvisionerOutput.length > 0 ? (
                          <div className="structured-plan-provisionerOutput">
                            <button
                              aria-expanded={expandedAttributeKeys.includes(`${row.key}-provisioner`)}
                              className="structured-plan-hiddenCountToggle"
                              onClick={() => toggleAttributesExpanded(`${row.key}-provisioner`)}
                              type="button"
                            >
                              <span
                                aria-hidden="true"
                                className={`structured-plan-chevron structured-plan-hiddenCountChevron${
                                  expandedAttributeKeys.includes(`${row.key}-provisioner`) ? " structured-plan-chevron--expanded" : ""
                                }`}
                              >
                                <RightOutlined />
                              </span>
                              <span>
                                Provisioner output{row.applyCurrentProvisioner ? ` (${row.applyCurrentProvisioner})` : ""}
                              </span>
                            </button>
                            {expandedAttributeKeys.includes(`${row.key}-provisioner`) ? (
                              <pre className="structured-plan-provisionerOutputContent">
                                {row.applyProvisionerOutput.join("\n")}
                              </pre>
                            ) : null}
                          </div>
                        ) : null}
                      </div>
                    </div>
                  ) : null}
                </div>
              );
            })
          ) : (
            <div className="structured-plan-emptyState">
              <Empty description={emptyDescription} image={Empty.PRESENTED_IMAGE_SIMPLE} />
            </div>
          )}
        </div>

        {applyMode && outputs?.length ? (
          <div className="structured-plan-outputs">
            <button
              aria-controls="structured-plan-outputsPanel"
              aria-expanded={areOutputsExpanded}
              className="structured-plan-outputsToggle"
              onClick={toggleOutputsExpanded}
              type="button"
            >
              <span
                className={`structured-plan-chevron${areOutputsExpanded ? " structured-plan-chevron--expanded" : ""}`}
              >
                <RightOutlined />
              </span>
              <span className="structured-plan-outputsHeader">Outputs</span>
              <span className="structured-plan-outputsCount">{outputs.length}</span>
            </button>
            {areOutputsExpanded ? (
              <div className="structured-plan-outputsList" id="structured-plan-outputsPanel">
                {outputs.map((output) => {
                  if (output.sensitive) {
                    return (
                      <div className="structured-plan-outputRow" key={output.name}>
                        <span className="structured-plan-outputName">{output.name}</span>
                        {renderValueToken(output.value, "sensitive", { sensitive: true, quoteStrings: false })}
                      </div>
                    );
                  }

                  const node = buildOutputValueNode(output.value, output.name, output.name);

                  if (!node.children?.length) {
                    return (
                      <div className="structured-plan-outputRow" key={output.name}>
                        <span className="structured-plan-outputName">{output.name}</span>
                        {renderValueToken(node.value, "unchanged", { quoteStrings: false })}
                        {typeof node.value === "string" ? (
                          <button
                            aria-label={`Copy value for ${output.name}`}
                            className="structured-plan-copyButton"
                            onClick={() => void handleCopyValue(String(node.value))}
                            title="Copy value"
                            type="button"
                          >
                            <CopyOutlined />
                          </button>
                        ) : null}
                      </div>
                    );
                  }

                  const isOutputExpanded = expandedOutputNames.includes(output.name);
                  const outputPanelId = `structured-plan-output-${output.name}`;

                  return (
                    <div className="structured-plan-outputGroup" key={output.name}>
                      <button
                        aria-controls={outputPanelId}
                        aria-expanded={isOutputExpanded}
                        className="structured-plan-outputGroupToggle"
                        onClick={() => toggleOutputExpanded(output.name)}
                        type="button"
                      >
                        <span
                          className={`structured-plan-chevron${isOutputExpanded ? " structured-plan-chevron--expanded" : ""}`}
                        >
                          <RightOutlined />
                        </span>
                        <span className="structured-plan-outputName">{output.name}</span>
                        {node.jsonEncoded ? <span className="structured-plan-jsonWrap">jsonencode({"{"}</span> : null}
                        {!isOutputExpanded ? (
                          <span className="structured-plan-outputsCount">
                            {node.children.length} attribute{getPluralSuffix(node.children.length)}
                          </span>
                        ) : null}
                      </button>
                      {isOutputExpanded ? (
                        <div className="structured-plan-diffGroupChildren" id={outputPanelId}>
                          {node.children.map((child) => renderOutputValueNode(child))}
                        </div>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
};
