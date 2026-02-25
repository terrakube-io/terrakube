import { Editor, type OnMount, type OnValidate } from "@monaco-editor/react";
import { Alert, Button, Form, Input, message, Space, theme } from "antd";
import { Buffer } from "buffer";
import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { getMonacoTheme, monacoOptions } from "../../config/monacoConfig";
import { Template } from "../types";
import "./Settings.css";

const validateMessages = {
  required: "${label} is required!",
};

type Props = {
  setMode: (mode: string) => void;
  templateId: string;
  loadTemplates: () => void;
};

type IStandaloneCodeEditor = Parameters<OnMount>[0];
type IMarkerArray = Parameters<OnValidate>[0];

type EditTemplateForm = {
  name: string;
  description?: string;
  tcl: string;
  version: string;
};

export const EditTemplate = ({ setMode, templateId, loadTemplates }: Props) => {
  const { orgid } = useParams();
  const [tcl, setTCL] = useState("");
  const editorRef = useRef<IStandaloneCodeEditor>(null);
  const [template, setTemplate] = useState<Template>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { token } = theme.useToken();

  function handleEditorDidMount(editor: IStandaloneCodeEditor) {
    editorRef.current = editor;
  }

  useEffect(() => {
    loadTemplate(templateId);
  }, [templateId]);

  function handleEditorValidation(markers: IMarkerArray) {
    markers.forEach((marker) => console.log("onValidate:", marker.message));
  }

  const loadTemplate = (templateId: string) => {
    axiosInstance
      .get(`organization/${orgid}/template/${templateId}`)
      .then((response) => {
        setTemplate(response.data.data);
        const buff = Buffer.from(response.data.data.attributes.tcl, "base64");
        setTCL(buff.toString("ascii"));
      })
      .catch((err) => {
        setError(getErrorMessage(err));
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const onFinish = (values: EditTemplateForm) => {
    const body = {
      data: {
        type: "template",
        id: templateId,
        attributes: {
          name: values.name,
          description: values.description,
          tcl: Buffer.from(editorRef.current.getValue()).toString("base64"),
          version: "1.0.0",
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}/template/${templateId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status == 204) {
          message.success("Template updated successfully");
          setMode("list");
          loadTemplates();
        }
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };
  return (
    <div>
      <h1>Edit Template</h1>
      <Space className="chooseType" direction="vertical">
        {loading ? (
          <p>Data loading...</p>
        ) : error ? (
          <Alert message="Error" description={error} type="error" showIcon />
        ) : template ? (
          <Form
            initialValues={{ name: template.attributes.name, description: template.attributes.description }}
            onFinish={onFinish}
            validateMessages={validateMessages}
            name="create-vcs"
            layout="vertical"
          >
            <Form.Item
              name="name"
              label="Name"
              extra=" A name for your Template. This will appear in the workspaces when you execute a new job."
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item name="description" label="Description">
              <Input.TextArea />
            </Form.Item>
            <Form.Item name="tcl" label="Template">
              <div className="editor">
                <Editor
                  height="40vh"
                  onMount={handleEditorDidMount}
                  onValidate={handleEditorValidation}
                  defaultLanguage="yaml"
                  defaultValue={tcl}
                  theme={getMonacoTheme(token.colorBgContainer === "#141414" ? "dark" : "light")}
                  options={monacoOptions}
                />
              </div>
            </Form.Item>
            <Button type="primary" htmlType="submit">
              Save Template
            </Button>
          </Form>
        ) : (
          <p>Failed to load template...</p>
        )}
      </Space>
    </div>
  );
};
