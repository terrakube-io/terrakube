import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { AutoComplete, Button, Flex, Input, message, Modal, Space, Typography } from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceTagBinding } from "@/modules/workspaces/types";
import { TAG_KEY_MAX_LENGTH, TAG_VALUE_MAX_LENGTH } from "@/modules/workspaces/utils/tagLimits";
import WorkspaceTagChips from "@/modules/workspaces/components/WorkspaceTagChips";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";
import workspaceService from "@/modules/workspaces/workspaceService";
import { Tag, ApiWorkspaceTag } from "../types";
import "./Workspaces.css";

type Props = {
  organizationId: string;
  workspaceId: string;
  manageWorkspace: boolean;
};

type SavedTag = WorkspaceTagBinding & { id: string };

/** One row of the editor. A row with a binding id is a saved tag, whose key is fixed. */
type EditorRow = {
  rowKey: string;
  bindingId?: string;
  key: string;
  value: string;
};

type TagChange =
  | { kind: "remove"; binding: SavedTag }
  | { kind: "update"; binding: SavedTag; value: string }
  | { kind: "add"; row: EditorRow };

const JSON_API_HEADERS = { "Content-Type": "application/vnd.api+json" };

const toSavedTag = ({ id, attributes }: ApiWorkspaceTag): SavedTag => ({
  id,
  tagId: attributes.tagId,
  value: attributes.value ?? null,
});

const toTagModel = (tag: Tag): TagModel => ({ id: tag.id, name: tag.attributes.name });

/**
 * The tags of one workspace as chips, with the editor in a modal: the sidebar is too narrow for a key and a
 * value input side by side. The editor works on a copy of the tags; Save sends every change and Cancel drops
 * them. The key of a saved tag is fixed, since a workspace holds one value per key.
 */
export const Tags = ({ organizationId, workspaceId, manageWorkspace }: Props) => {
  const [tags, setTags] = useState<TagModel[]>([]);
  const [bindings, setBindings] = useState<SavedTag[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [rows, setRows] = useState<EditorRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const nextRowKey = useRef(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      axiosInstance.get(`organization/${organizationId}/workspace/${workspaceId}/workspaceTag`),
      axiosInstance.get(`organization/${organizationId}/tag`),
    ])
      .then(([workspaceTags, organizationTags]) => {
        if (cancelled) return;
        setBindings((workspaceTags.data.data ?? []).map(toSavedTag));
        setTags((organizationTags.data.data ?? []).map(toTagModel));
      })
      .catch((err) => {
        if (!cancelled) message.error(getErrorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [organizationId, workspaceId]);

  const newRowKey = () => `row-${nextRowKey.current++}`;

  const keyOptions = useMemo(() => {
    const used = new Set(rows.map((row) => row.key.trim()));
    return tags.filter((tag) => !used.has(tag.name)).map((tag) => ({ value: tag.name }));
  }, [tags, rows]);

  const findOrCreateTag = async (name: string): Promise<string> => {
    const known = tags.find((tag) => tag.name === name);
    if (known) return known.id;

    // Another user may have created the key since the list was loaded
    const existing = await axiosInstance.get(`organization/${organizationId}/tag`, {
      params: { "filter[tag]": `name==${quoteRsql(name)}` },
    });
    const existingId = existing.data?.data?.[0]?.id;
    if (existingId) return existingId;

    const created = await axiosInstance.post(
      `organization/${organizationId}/tag`,
      { data: { type: "tag", attributes: { name } } },
      { headers: JSON_API_HEADERS }
    );
    setTags((prev) => [...prev, toTagModel(created.data.data)]);
    return created.data.data.id;
  };

  const openEditor = () => {
    setRows(
      bindings.map((binding) => ({
        rowKey: newRowKey(),
        bindingId: binding.id,
        key: tags.find((tag) => tag.id === binding.tagId)?.name ?? binding.tagId,
        value: binding.value ?? "",
      }))
    );
    setError(null);
    setEditorOpen(true);
  };

  const updateRow = (rowKey: string, patch: Partial<EditorRow>) =>
    setRows((prev) => prev.map((row) => (row.rowKey === rowKey ? { ...row, ...patch } : row)));

  /** What Save has to send to turn the saved tags into the rows, or why it cannot. */
  const changesFor = (current: SavedTag[]): TagChange[] | string => {
    // A new row left completely empty is skipped rather than refused
    const kept = rows.filter((row) => row.bindingId || row.key.trim() || row.value.trim());
    if (kept.some((row) => !row.key.trim())) return "Every tag needs a key.";
    const keys = kept.map((row) => row.key.trim());
    const duplicate = keys.find((key, index) => keys.indexOf(key) !== index);
    if (duplicate) return `A workspace holds one value per key, but "${duplicate}" is listed more than once.`;

    // Removals go first, so a key removed and added again in the same edit does not collide with itself
    const changes: TagChange[] = current
      .filter((binding) => !kept.some((row) => row.bindingId === binding.id))
      .map((binding) => ({ kind: "remove", binding }));
    for (const row of kept) {
      const binding = current.find((saved) => saved.id === row.bindingId);
      if (!binding) changes.push({ kind: "add", row });
      else if ((binding.value ?? "") !== row.value.trim())
        changes.push({ kind: "update", binding, value: row.value.trim() });
    }
    return changes;
  };

  /** Sends one change and returns the saved tag it leaves behind, if any. */
  const apply = async (change: TagChange): Promise<SavedTag | undefined> => {
    const base = `organization/${organizationId}/workspace/${workspaceId}/workspaceTag`;
    if (change.kind === "remove") {
      await axiosInstance.delete(`${base}/${change.binding.id}`);
      return undefined;
    }
    if (change.kind === "update") {
      // An empty input and a tag that never had a value are the same thing, so both are stored as null
      const value = change.value || null;
      await axiosInstance.patch(
        `${base}/${change.binding.id}`,
        { data: { type: "workspacetag", id: change.binding.id, attributes: { value } } },
        { headers: JSON_API_HEADERS }
      );
      return { ...change.binding, value };
    }
    const tagId = await findOrCreateTag(change.row.key.trim());
    const response = await axiosInstance.post(
      base,
      { data: { type: "workspacetag", attributes: { tagId, value: change.row.value.trim() || null } } },
      { headers: JSON_API_HEADERS }
    );
    return toSavedTag(response.data.data);
  };

  const save = async () => {
    const changes = changesFor(bindings);
    if (typeof changes === "string") {
      setError(changes);
      return;
    }

    setSaving(true);
    setError(null);
    let current = bindings;
    const added = new Map<string, string>();
    let failures = 0;
    for (const change of changes) {
      try {
        const saved = await apply(change);
        if (change.kind === "add") added.set(change.row.rowKey, saved!.id);
        else current = current.filter((binding) => binding.id !== change.binding.id);
        if (saved) current = [...current, saved];
      } catch (err) {
        failures++;
        message.error(getErrorMessage(err));
      }
    }
    setBindings(current);
    setSaving(false);
    if (failures < changes.length) {
      // The workspace list reuses the workspaces a tag filter matched for a while; this one may have moved
      workspaceService.clearTagFilterCache();
    }
    if (failures === 0) {
      setEditorOpen(false);
      return;
    }
    // What went through stays saved. The rows still describe the wanted tags, so once the added ones know
    // their binding, Save sends only the changes that failed.
    setRows((prev) => prev.map((row) => (added.has(row.rowKey) ? { ...row, bindingId: added.get(row.rowKey) } : row)));
    setError(`${failures} of ${changes.length} changes could not be saved. The others were saved.`);
  };

  return (
    <Space orientation="vertical" size={8}>
      {bindings.length > 0 ? (
        <Flex wrap gap={4}>
          <WorkspaceTagChips bindings={bindings} tags={tags} />
        </Flex>
      ) : (
        !loading && <Typography.Text type="secondary">No tags</Typography.Text>
      )}
      {manageWorkspace && (
        <Button size="small" icon={<EditOutlined />} loading={loading} onClick={openEditor}>
          Edit tags
        </Button>
      )}
      <Modal
        width="640px"
        open={editorOpen}
        title="Edit tags"
        okText="Save"
        onOk={save}
        confirmLoading={saving}
        onCancel={() => setEditorOpen(false)}
        cancelButtonProps={{ disabled: saving }}
        closable={!saving}
        keyboard={!saving}
        mask={{ closable: false }}
      >
        <Space orientation="vertical" className="workspace-tag-editor" size={8}>
          {rows.length === 0 && <Typography.Text type="secondary">No tags on this workspace yet.</Typography.Text>}
          {rows.map((row) => (
            <Flex key={row.rowKey} gap={8} align="center">
              {row.bindingId ? (
                <Input aria-label="Tag key" value={row.key} disabled className="workspace-tag-editor-field" />
              ) : (
                <AutoComplete
                  aria-label="New tag key"
                  options={keyOptions}
                  value={row.key}
                  placeholder="Key"
                  maxLength={TAG_KEY_MAX_LENGTH}
                  showSearch={{
                    filterOption: (input, option) => (option?.value ?? "").toLowerCase().includes(input.toLowerCase()),
                  }}
                  onChange={(key) => updateRow(row.rowKey, { key })}
                  className="workspace-tag-editor-field"
                />
              )}
              <Input
                aria-label={row.bindingId ? `Value of ${row.key}` : "New tag value"}
                value={row.value}
                placeholder={row.bindingId ? "No value" : "Value (optional)"}
                maxLength={TAG_VALUE_MAX_LENGTH}
                onChange={(e) => updateRow(row.rowKey, { value: e.target.value })}
                className="workspace-tag-editor-field"
              />
              <Button
                type="text"
                aria-label={`Remove ${row.key || "new tag"}`}
                icon={<DeleteOutlined />}
                onClick={() => setRows((prev) => prev.filter((current) => current.rowKey !== row.rowKey))}
              />
            </Flex>
          ))}
          <Button
            type="dashed"
            icon={<PlusOutlined />}
            onClick={() => setRows((prev) => [...prev, { rowKey: newRowKey(), key: "", value: "" }])}
          >
            Add tag
          </Button>
          {error && <Typography.Text type="danger">{error}</Typography.Text>}
        </Space>
      </Modal>
    </Space>
  );
};
