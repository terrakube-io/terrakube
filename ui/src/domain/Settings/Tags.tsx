import { DeleteOutlined, EditOutlined, InfoCircleOutlined, PlusOutlined, TagOutlined } from "@ant-design/icons";
import {
  Alert,
  Avatar,
  Button,
  Form,
  Input,
  List,
  message,
  Modal,
  Popconfirm,
  Space,
  Typography,
  theme,
  Spin,
} from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Tag } from "../types";
import "./Settings.css";

type Props = {
  managePermission?: boolean;
};

type AddTagForm = {
  name: string;
};

export const TagsSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [tags, setTags] = useState<Tag[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [visible, setVisible] = useState(false);
  const [tagName, setTagName] = useState<string>();
  const [mode, setMode] = useState("create");
  const [tagId, setTagId] = useState<string>();
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
        <Alert message="Access Denied" description={error} type="error" showIcon />
      ) : (
        <>
          <h1>Tag Management</h1>
          <div>
            <Typography.Text type="secondary" className="App-text">
              Tags are used to help identify and group together workspaces..
            </Typography.Text>
          </div>
          <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />} disabled={!managePermission}>
            Create tag
          </Button>
          <br></br>

          <h3 style={{ marginTop: "30px" }}>Tags</h3>
          <Spin spinning={loading} tip="Loading Tags...">
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
                    <Popconfirm
                      onConfirm={() => {
                        onDelete(item.id);
                      }}
                      style={{ width: "20px" }}
                      title={
                        <p>
                          Deleting this tag will also remove it <br />
                          from all the Workspaces that use it.
                          <br />
                          This action cannot be undone. <br />
                          Are you sure?
                        </p>
                      }
                      okText="Yes"
                      cancelText="No"
                    >
                      {" "}
                      <Button icon={<DeleteOutlined />} type="link" danger disabled={!managePermission}>
                        Delete
                      </Button>
                    </Popconfirm>,
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

          <Modal
            width="600px"
            open={visible}
            title={mode === "edit" ? "Edit tag " + tagName : "Create new tag"}
            okText="Save tag"
            onCancel={onCancel}
            cancelText="Cancel"
            onOk={() => {
              form
                .validateFields()
                .then((values) => {
                  if (mode === "create") onCreate(values);
                  else onUpdate(values);
                })
                .catch((info) => {
                  console.log("Validate Failed:", info);
                });
            }}
          >
            <Space style={{ width: "100%" }} direction="vertical">
              <Form name="tag" form={form} layout="vertical">
                <Form.Item
                  name="name"
                  tooltip={{
                    title: "Must be a valid tag name",
                    icon: <InfoCircleOutlined />,
                  }}
                  label="Name"
                  rules={[{ required: true }]}
                >
                  <Input />
                </Form.Item>
              </Form>
            </Space>
          </Modal>
        </>
      )}
    </div>
  );
};
