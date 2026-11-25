import { Button, Flex, Input, Modal, Select, Tag, Typography, message } from "antd";
import { MouseEvent, useCallback, useEffect, useState } from "react";
import projectService, { OrganizationTag, ProjectDefaultTag } from "./projectService";

type Props = {
  orgid: string;
  projectId: string;
  canManage: boolean;
};

/**
 * Tags that are copied onto every workspace created in this project or moved into it. Workspaces that are already
 * in the project are not changed, and removing a tag here does not remove it from workspaces that have it.
 */
export default function ProjectDefaultTags({ orgid, projectId, canManage }: Props) {
  const [defaults, setDefaults] = useState<ProjectDefaultTag[]>([]);
  const [tags, setTags] = useState<OrganizationTag[]>([]);
  const [tagId, setTagId] = useState<string>();
  const [value, setValue] = useState("");
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    try {
      const [current, available] = await Promise.all([
        projectService.listProjectDefaultTags(orgid, projectId),
        projectService.listOrganizationTags(orgid),
      ]);
      setDefaults(current);
      setTags(available);
    } catch (err: any) {
      message.error(err?.message ?? "Failed to load the default tags");
    }
  }, [orgid, projectId]);

  useEffect(() => {
    load();
  }, [load]);

  const nameOf = (id: string) => tags.find((tag) => tag.id === id)?.name ?? id;
  const unused = tags.filter((tag) => !defaults.some((item) => item.tagId === tag.id));

  const add = async () => {
    if (!tagId || adding) return;
    setAdding(true);
    try {
      await projectService.addProjectDefaultTag(orgid, projectId, tagId, value.trim());
      setTagId(undefined);
      setValue("");
      load();
    } catch (err: any) {
      message.error(err?.response?.status === 403 ? "You do not have permission to change this project" : err?.message);
    } finally {
      setAdding(false);
    }
  };

  const applyToExisting = async () => {
    try {
      const preview = await projectService.applyProjectDefaultTags(orgid, projectId, true);
      if (preview.tagsAdded === 0) {
        message.info("Every workspace in the project already has the default tags");
        return;
      }
      Modal.confirm({
        title: "Apply default tags to existing workspaces?",
        content: `${preview.tagsAdded} tag${preview.tagsAdded === 1 ? "" : "s"} will be added to ${preview.workspacesChanged} of ${preview.workspaces} workspace${preview.workspaces === 1 ? "" : "s"}. Tags a workspace already has are not changed.`,
        okText: "Apply",
        onOk: async () => {
          try {
            const done = await projectService.applyProjectDefaultTags(orgid, projectId, false);
            message.success(`Added ${done.tagsAdded} tag${done.tagsAdded === 1 ? "" : "s"}`);
          } catch (err: any) {
            message.error(
              err?.response?.status === 404
                ? "You do not have permission to change this project"
                : (err?.message ?? "The default tags could not be applied")
            );
          }
        },
      });
    } catch (err: any) {
      message.error(
        err?.response?.status === 404 ? "You do not have permission to change this project" : (err?.message ?? "Failed")
      );
    }
  };

  // The tag stays on screen until the delete has worked; the reload then drops it.
  const remove = async (event: MouseEvent<HTMLElement>, item: ProjectDefaultTag) => {
    event.preventDefault();
    try {
      await projectService.removeProjectDefaultTag(orgid, projectId, item.id);
      load();
    } catch (err: any) {
      message.error(err?.message ?? "Failed to remove the tag");
    }
  };

  return (
    <div className="project-default-tags">
      <h3>Default tags</h3>
      <Typography.Paragraph type="secondary">
        Copied onto workspaces created in this project or moved into it. Use Apply to existing workspaces to add them to
        the workspaces that are already here.
      </Typography.Paragraph>
      <Flex gap={8} wrap className="project-default-tags-list">
        {defaults.length === 0 && <Typography.Text type="secondary">No default tags.</Typography.Text>}
        {defaults.map((item) => (
          <Tag key={item.id} closable={canManage} onClose={(event) => remove(event, item)}>
            {item.value ? `${nameOf(item.tagId)}: ${item.value}` : nameOf(item.tagId)}
          </Tag>
        ))}
      </Flex>
      {canManage && (
        <Flex gap={8}>
          <Select
            showSearch
            optionFilterProp="label"
            aria-label="Tag to add"
            placeholder="Choose a tag"
            style={{ minWidth: 200 }}
            value={tagId}
            onChange={setTagId}
            options={unused.map((tag) => ({ value: tag.id, label: tag.name }))}
          />
          <Input
            aria-label="Tag value"
            placeholder="Value (optional)"
            maxLength={256}
            style={{ width: 200 }}
            value={value}
            onChange={(event) => setValue(event.target.value)}
          />
          <Button type="primary" disabled={!tagId} loading={adding} onClick={add}>
            Add tag
          </Button>
          <Button disabled={defaults.length === 0} onClick={applyToExisting}>
            Apply to existing workspaces
          </Button>
        </Flex>
      )}
    </div>
  );
}
