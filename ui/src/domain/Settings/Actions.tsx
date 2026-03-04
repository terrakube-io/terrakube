import {
  DeleteOutlined,
  EditOutlined,
  InfoCircleOutlined,
  MinusCircleOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";
import { Editor, type OnMount } from "@monaco-editor/react";
import {
  Alert,
  Button,
  Form,
  Input,
  message,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tooltip,
  Typography,
  theme,
} from "antd";
import { Buffer } from "buffer";
import { useEffect, useRef, useState } from "react";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { getMonacoTheme, monacoOptions } from "../../config/monacoConfig";
import { Action } from "../types";
import "./Settings.css";

const validateMessages: any = {
  required: "${label} is required!",
  types: {
    version: "${label} is not a valid semver!",
  },
  pattern: {
    mismatch: "${label} is not a valid semver!",
  },
};

type IStandaloneCodeEditor = Parameters<OnMount>[0];

type CreateActionForm = {
  id: string;
} & EditActionForm;
type EditActionForm = {
  action: string;
  active: boolean;
  category: string;
  description: string;
  displayCriteria: string;
  label: string;
  name: string;
  type: string;
  version: string;
};

type Props = {
  managePermission?: boolean;
};

export const ActionSettings = ({ managePermission = true }: Props) => {
  const [actions, setActions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [mode, setMode] = useState("create");
  const [actionId, setActionId] = useState<string>();
  const [actionContent, setActionContent] = useState<string>("");
  const [form] = Form.useForm();
  const editorRef = useRef<IStandaloneCodeEditor>(null);
  const { token } = theme.useToken();

  const ACTIONS_COLUMNS = (onEdit: (id: string) => void) => [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      render: (_: string, record: Action) => <div>{record.attributes.name}</div>,
    },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      render: (_: string, record: Action) => <div>{record.attributes.type}</div>,
    },
    {
      title: "Category",
      dataIndex: "category",
      key: "category",
      render: (_: string, record: Action) => <div>{record.attributes.category}</div>,
    },
    {
      title: "Version",
      dataIndex: "version",
      key: "version",
      render: (_: string, record: Action) => <div>{record.attributes.version}</div>,
    },
    {
      title: "Active",
      dataIndex: "active",
      key: "active",
      render: (_: string, record: Action) => <Switch checked={record.attributes.active} disabled />,
    },
    {
      title: "Actions",
      key: "action",
      render: (_: string, record: Action) => (
        <div>
          <Button type="link" icon={<EditOutlined />} onClick={() => onEdit(record.id)} disabled={!managePermission}>
            Edit
          </Button>
          <Popconfirm
            title="Are you sure to delete this action?"
            onConfirm={() => onDelete(record.id)}
            okText="Yes"
            cancelText="No"
          >
            <Button danger type="link" icon={<DeleteOutlined />} disabled={!managePermission}>
              Delete
            </Button>
          </Popconfirm>
          <Tooltip title="Open the documentation for this Action">
            <Button
              icon={<QuestionCircleOutlined />}
              target="_blank"
              rel="noreferrer"
              href={"https://docs.terrakube.io/user-guide/workspaces/actions/built-in-actions/" + record.id}
              type="link"
            ></Button>
          </Tooltip>
        </div>
      ),
    },
  ];

  const onCancel = () => {
    setIsEditing(false);
    form.resetFields();
    setActionContent("");
    if (editorRef.current) {
      editorRef.current.setValue("");
    }
  };

  const onEdit = (id: string) => {
    setMode("edit");
    setActionId(id);
    setIsEditing(true);
    axiosInstance
      .get(`action/${id}`)
      .then((response) => {
        const action = response.data.data;
        form.setFieldsValue({
          id: action.id,
          ...action.attributes,
          displayCriteria: JSON.parse(action.attributes.displayCriteria),
        });
        const actionDecoded = Buffer.from(action.attributes.action, "base64").toString("ascii");
        setActionContent(actionDecoded);
        if (editorRef.current) {
          editorRef.current.setValue(actionDecoded);
        }
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
        setIsEditing(false);
      });
  };

  const onNew = () => {
    form.resetFields();
    setIsEditing(true);
    setMode("create");
    setActionContent("");
    if (editorRef.current) {
      editorRef.current.setValue("");
    }
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`action/${id}`)
      .then(() => {
        message.success("Action deleted successfully");
        loadActions();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onCreate = (values: CreateActionForm) => {
    const editorValue = editorRef.current ? editorRef.current.getValue() : actionContent;
    const actionEncoded = Buffer.from(editorValue).toString("base64");
    const displayCriteria = JSON.stringify(values.displayCriteria);
    const body = {
      data: {
        type: "action",
        id: values.id,
        attributes: { ...values, action: actionEncoded, displayCriteria },
      },
    };
    axiosInstance
      .post(`action`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Action created successfully");
        loadActions();
        setIsEditing(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onUpdate = (values: EditActionForm) => {
    const editorValue = editorRef.current ? editorRef.current.getValue() : actionContent;
    const actionEncoded = Buffer.from(editorValue).toString("base64");
    const displayCriteria = JSON.stringify(values.displayCriteria);
    const body = {
      data: {
        type: "action",
        id: actionId,
        attributes: { ...values, action: actionEncoded, displayCriteria },
      },
    };
    axiosInstance
      .patch(`action/${actionId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Action updated successfully");
        loadActions();
        setIsEditing(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const loadActions = () => {
    axiosInstance
      .get(`action`)
      .then((response) => {
        setActions(response.data.data);
        setError(null);
      })
      .catch((err) => {
        setError(getErrorMessage(err));
      })
      .finally(() => {
        setLoading(false);
      });
  };

  useEffect(() => {
    setLoading(true);
    loadActions();
  }, []);

  function handleEditorDidMount(editor: IStandaloneCodeEditor) {
    editorRef.current = editor;
    if (actionContent) {
      editor.setValue(actionContent);
    }
  }

  return (
    <div className="setting">
      <h1>Actions</h1>
      <div>
        <Typography.Text type="secondary" className="App-text">
          Actions are used to extend the Terrakube UI. For example, you can add a new button to restart a VM directly
          from Terrakube.
        </Typography.Text>
      </div>
      {error ? (
        <Alert
          message={error.includes("permission") ? "Access Denied" : "Error"}
          description={error}
          type="error"
          showIcon
          style={{ marginTop: "20px" }}
        />
      ) : !isEditing ? (
        <>
          <Button type="primary" icon={<PlusOutlined />} onClick={onNew} disabled={!managePermission}>
            Create Action
          </Button>
          <h3 style={{ marginTop: "30px" }}>Actions</h3>
          {loading || !actions ? (
            <p>Data loading...</p>
          ) : (
            <Table dataSource={actions} columns={ACTIONS_COLUMNS(onEdit)} rowKey="id" />
          )}
        </>
      ) : (
        <div>
          <h3>{mode === "edit" ? "Edit Action" : "Create New Action"}</h3>
          {mode === "edit" ? (
            <Tooltip title="Open the documentation for this Action">
              <Button
                icon={<QuestionCircleOutlined />}
                target="_blank"
                rel="noreferrer"
                href={"https://docs.terrakube.io/user-guide/workspaces/actions/built-in-actions/" + actionId}
                type="link"
              >
                Action Documentation
              </Button>
            </Tooltip>
          ) : (
            <Tooltip title="See a quick start guide in how to create Actions">
              <Button
                icon={<QuestionCircleOutlined />}
                target="_blank"
                rel="noreferrer"
                href={"https://docs.terrakube.io/user-guide/workspaces/actions/developing-actions/quick-start"}
                type="link"
              >
                Actions Documentation
              </Button>
            </Tooltip>
          )}
          <Form
            form={form}
            layout="vertical"
            onFinish={(values) => {
              if (mode === "create") onCreate(values);
              else onUpdate(values);
            }}
            validateMessages={validateMessages}
          >
            <Form.Item name="id" label="ID" rules={[{ required: true }]}>
              <Input disabled={mode !== "create"} />
            </Form.Item>
            <Form.Item name="name" label="Name" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item
              name="type"
              label="Type"
              tooltip={{
                title:
                  "Defines the section where this action will appear. Check the docs to see the specific area where the action will be rendered.",
                icon: <InfoCircleOutlined />,
              }}
              rules={[{ required: true }]}
            >
              <Select placeholder="Please select a type">
                <Select.Option value="Workspace/Action">Workspace/Action</Select.Option>
                <Select.Option value="Workspace/ResourceDrawer/Action">Workspace/ResourceDrawer/Action</Select.Option>
                <Select.Option value="Workspace/ResourceDrawer/Tab">Workspace/ResourceDrawer/Tab</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item
              name="label"
              label="Label"
              tooltip={{
                title:
                  "For tabs, this will be displayed as the tab name. For action buttons, it should be displayed as the button name.",
                icon: <InfoCircleOutlined />,
              }}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="category"
              label="Category"
              tooltip={{
                title:
                  "This helps to organize the actions based on their function. Example: General, Azure, Cost, Monitoring.",
                icon: <InfoCircleOutlined />,
              }}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="version"
              label="Version"
              tooltip={{
                title: "Must follow semantic versioning (e.g., 1.0.0).",
                icon: <InfoCircleOutlined />,
              }}
              rules={[
                { required: true },
                {
                  pattern: new RegExp(/^([0-9]+)\.([0-9]+)\.([0-9]+)$/),
                  message: "Version must be in semver format (e.g., 1.0.0)",
                },
              ]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="description"
              label="Description"
              tooltip={{
                title: "A brief description of the action.",
                icon: <InfoCircleOutlined />,
              }}
            >
              <Input.TextArea />
            </Form.Item>
            <Form.List name="displayCriteria">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name, ...restField }) => (
                    <div key={key} style={{ marginBottom: 16 }}>
                      <Space
                        style={{ display: "flex", justifyContent: "space-between", width: "100%" }}
                        align="baseline"
                      >
                        <Form.Item
                          {...restField}
                          name={[name, "filter"]}
                          tooltip={{
                            title: "Defines if the action appears or not.",
                            icon: <InfoCircleOutlined />,
                          }}
                          style={{ width: "calc(100% - 24px)" }}
                          rules={[{ required: true, message: "Missing filter" }]}
                        >
                          <Input placeholder="Filter" />
                        </Form.Item>
                        <Tooltip title="Remove Display Criteria">
                          <MinusCircleOutlined onClick={() => remove(name)} />
                        </Tooltip>
                      </Space>
                      <Form.List name={[name, "settings"]}>
                        {(settingFields, { add: addSetting, remove: removeSetting }) => (
                          <>
                            {settingFields.map(({ key: settingKey, name: settingName, ...settingRestField }) => (
                              <Space
                                key={settingKey}
                                style={{ display: "flex", marginBottom: 8, marginLeft: 24 }}
                                align="baseline"
                              >
                                <Form.Item
                                  {...settingRestField}
                                  name={[settingName, "key"]}
                                  rules={[{ required: true, message: "Missing key" }]}
                                >
                                  <Input placeholder="Key" />
                                </Form.Item>
                                <Form.Item
                                  {...settingRestField}
                                  name={[settingName, "value"]}
                                  rules={[{ required: true, message: "Missing value" }]}
                                >
                                  <Input placeholder="Value" />
                                </Form.Item>
                                <Tooltip title="Remove Setting">
                                  <MinusCircleOutlined onClick={() => removeSetting(settingName)} />
                                </Tooltip>
                              </Space>
                            ))}
                            <Form.Item style={{ marginLeft: 24 }}>
                              <Button type="dashed" onClick={() => addSetting()} block icon={<PlusOutlined />}>
                                Add Setting
                              </Button>
                            </Form.Item>
                          </>
                        )}
                      </Form.List>
                    </div>
                  ))}
                  <Form.Item>
                    <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />}>
                      Add Display Criteria
                    </Button>
                  </Form.Item>
                </>
              )}
            </Form.List>
            <Form.Item
              label="Action"
              tooltip={{
                title:
                  "A JavaScript function equivalent to a React component. Receives the context with some data related to the context. This varies by type, please check the docs.",
                icon: <InfoCircleOutlined />,
              }}
            >
              <div className="editor">
                <Editor
                  height="40vh"
                  onMount={handleEditorDidMount}
                  defaultLanguage="javascript"
                  theme={getMonacoTheme(token.colorBgContainer === "#141414" ? "dark" : "light")}
                  options={monacoOptions}
                />
              </div>
            </Form.Item>
            <Form.Item name="active" valuePropName="checked" label="Active">
              <Switch />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" disabled={!managePermission}>
                Save
              </Button>
              <Button type="default" onClick={onCancel} style={{ marginLeft: "10px" }}>
                Cancel
              </Button>
            </Form.Item>
          </Form>
        </div>
      )}
    </div>
  );
};
