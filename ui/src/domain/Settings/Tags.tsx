import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Flex, Form, message, Table, Tooltip, Typography } from "antd";
import { Loading } from "@/components/feedback/Loading";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Tag } from "../types";
import "./Settings.css";
import "./TeamsTagsVariables.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { EmptyState } from "@/components/feedback/EmptyState";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import TagFormModal, { TagFormValues } from "./components/TagFormModal";

type Props = {
  managePermission?: boolean;
};

type AddTagForm = TagFormValues;

export const TagsSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [tags, setTags] = useState<Tag[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [visible, setVisible] = useState(false);
  const [tagName, setTagName] = useState<string>();
  const [mode, setMode] = useState("create");
  const [tagId, setTagId] = useState<string>();
  const [pendingDelete, setPendingDelete] = useState<Tag | null>(null);
  const [form] = Form.useForm<AddTagForm>();

  const onCancel = () => {
    setVisible(false);
  };
  const onEdit = (id: string) => {
    setMode("edit");
    setTagId(id);
    setVisible(true);
    axiosInstance
      .get(`organization/${orgid}/tag/${id}`)
      .then((response) => {
        setTagName(response.data.data.attributes.name);
        form.setFieldsValue({
          name: response.data.data.attributes.name,
        });
      })
      .catch((err) => {
        message.error(`Could not load the tag: ${getErrorMessage(err)}`);
      });
  };

  const onNew = () => {
    form.resetFields();
    setVisible(true);
    setTagName("");
    setMode("create");
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/tag/${id}`)
      .then(() => {
        message.success("Tag deleted");
        loadTags();
      })
      .catch((err) => {
        message.error(`Could not delete the tag: ${getErrorMessage(err)}`);
      });
  };

  const onCreate = (values: AddTagForm) => {
    const body = {
      data: {
        type: "tag",
        attributes: {
          name: values.name,
        },
      },
    };

    axiosInstance
      .post(`organization/${orgid}/tag`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Tag created");
        loadTags();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(`Could not create the tag: ${getErrorMessage(err)}`);
      });
  };

  const onUpdate = (values: AddTagForm) => {
    const body = {
      data: {
        type: "tag",
        id: tagId,
        attributes: {
          name: values.name,
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}/tag/${tagId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Tag updated");
        loadTags();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(`Could not save the tag: ${getErrorMessage(err)}`);
      });
  };

  const loadTags = () => {
    axiosInstance
      .get(`organization/${orgid}/tag`)
      .then((response) => {
        setTags(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error(`Could not load tags: ${getErrorMessage(err)}`);
        }
        setLoading(false);
      });
  };
  useEffect(() => {
    setLoading(true);
    loadTags();
  }, [orgid]);

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/organizations/tags"
            title="Tags"
            description="Tag keys group related workspaces so they are easier to find and filter. Each workspace sets its own value for a key."
            divider={false}
            actions={
              <Button
                type="primary"
                onClick={onNew}
                htmlType="button"
                icon={<PlusOutlined />}
                disabled={!managePermission}
              >
                Create tag key
              </Button>
            }
          />
          <Loading loading={loading} description="Loading tags...">
            {tags.length === 0 ? (
              <EmptyState simple description="No tag keys yet. Create one, then give it a value on workspaces.">
                {managePermission && (
                  <Button icon={<PlusOutlined />} onClick={onNew}>
                    Create tag key
                  </Button>
                )}
              </EmptyState>
            ) : (
              <section>
                <Typography.Title level={4} className="settings-list-count">
                  Tags ({tags.length})
                </Typography.Title>
                <Table
                  dataSource={tags}
                  rowKey="id"
                  scroll={{ x: "max-content" }}
                  pagination={{
                    pageSize: 10,
                    showSizeChanger: true,
                    pageSizeOptions: ["10", "20", "50"],
                    hideOnSinglePage: true,
                    showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} tags`,
                  }}
                  columns={[
                    {
                      title: "Key",
                      key: "name",
                      render: (_: unknown, tag: Tag) => tag.attributes.name,
                    },
                    {
                      title: <span className="settings-list-sr-only">Actions</span>,
                      key: "actions",
                      align: "right" as const,
                      render: (_: unknown, tag: Tag) => (
                        <Flex gap="small" justify="flex-end">
                          <Tooltip title="Edit tag">
                            <Button
                              icon={<EditOutlined />}
                              aria-label={`Edit tag ${tag.attributes.name}`}
                              disabled={!managePermission}
                              onClick={() => onEdit(tag.id)}
                            />
                          </Tooltip>
                          <Tooltip title="Delete tag">
                            <Button
                              danger
                              icon={<DeleteOutlined />}
                              aria-label={`Delete tag ${tag.attributes.name}`}
                              disabled={!managePermission}
                              onClick={() => setPendingDelete(tag)}
                            />
                          </Tooltip>
                        </Flex>
                      ),
                    },
                  ]}
                />
              </section>
            )}
          </Loading>

          <TagFormModal
            open={visible}
            mode={mode === "create" ? "create" : "edit"}
            tagName={tagName}
            form={form}
            onCancel={onCancel}
            onSubmit={(values) => {
              if (mode === "create") onCreate(values);
              else onUpdate(values);
            }}
          />

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete tag"
            message={
              <>
                The tag <strong>{pendingDelete?.attributes.name}</strong> will be removed from every workspace that uses
                it. This cannot be undone.
              </>
            }
            okText="Delete tag"
            onConfirm={() => {
              if (pendingDelete) onDelete(pendingDelete.id);
              setPendingDelete(null);
            }}
            onCancel={() => setPendingDelete(null)}
          />
        </>
      )}
    </div>
  );
};
