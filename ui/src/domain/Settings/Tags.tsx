import { DeleteOutlined, EditOutlined, PlusOutlined, TagOutlined } from "@ant-design/icons";
import { Avatar, Button, Form, List, message, theme, Spin } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Tag } from "../types";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
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
  const { token } = theme.useToken();

  const onCancel = () => {
    setVisible(false);
  };
  const onEdit = (id: string) => {
    setMode("edit");
    setTagId(id);
    setVisible(true);
    axiosInstance.get(`organization/${orgid}/tag/${id}`).then((response) => {
      setTagName(response.data.data.attributes.name);
      form.setFieldsValue({
        name: response.data.data.attributes.name,
      });
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
      .then((response) => {
        loadTags();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
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
      .then((response) => {
        loadTags();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
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
        loadTags();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
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
          message.error("Failed to load tags");
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
            title="Tag Management"
            description="Tags are used to help identify and group together workspaces.."
            actions={
              <Button
                type="primary"
                onClick={onNew}
                htmlType="button"
                icon={<PlusOutlined />}
                disabled={!managePermission}
              >
                Create tag
              </Button>
            }
          />
          <Spin spinning={loading} description="Loading Tags...">
            <List
              itemLayout="horizontal"
              dataSource={tags}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Button
                      onClick={() => {
                        onEdit(item.id);
                      }}
                      icon={<EditOutlined />}
                      type="link"
                      disabled={!managePermission}
                    >
                      Edit
                    </Button>,
                    <Button
                      icon={<DeleteOutlined />}
                      type="link"
                      danger
                      disabled={!managePermission}
                      onClick={() => setPendingDelete(item)}
                    >
                      Delete
                    </Button>,
                  ]}
                >
                  <List.Item.Meta
                    avatar={<Avatar style={{ backgroundColor: token.colorPrimary }} icon={<TagOutlined />}></Avatar>}
                    title={item.attributes.name}
                  />
                </List.Item>
              )}
            />
          </Spin>

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
                Deleting the tag <strong>{pendingDelete?.attributes.name}</strong> cannot be undone. It will also be
                removed from all the workspaces that use it.
              </>
            }
            okText="Delete"
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
