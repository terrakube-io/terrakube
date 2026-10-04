import type { OnMount } from "@monaco-editor/react";
import { CodeEditor } from "@/components/forms/CodeEditor";
import { Alert, Form, Input, message } from "antd";
import { Buffer } from "buffer";
import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { Template } from "../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import "./EditorForm.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { IdField } from "@/components/settings/IdField";
import { DangerZone } from "@/components/settings/DangerZone";
import LoadingFallback from "@/components/feedback/LoadingFallback";

type Props = {
  setMode: (mode: string) => void;
  templateId: string;
  loadTemplates: () => void;
  managePermission?: boolean;
};

type IStandaloneCodeEditor = Parameters<OnMount>[0];

type EditTemplateForm = {
  name: string;
  description?: string;
  tcl: string;
  version: string;
};

export const EditTemplate = ({ setMode, templateId, loadTemplates, managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [tcl, setTCL] = useState("");
  const editorRef = useRef<IStandaloneCodeEditor>(null);
  const [template, setTemplate] = useState<Template>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  function handleEditorDidMount(editor: IStandaloneCodeEditor) {
    editorRef.current = editor;
  }

  useEffect(() => {
    loadTemplate(templateId);
  }, [templateId]);

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
          tcl: Buffer.from(editorRef.current?.getValue() ?? tcl).toString("base64"),
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

  const onDelete = () => {
    axiosInstance
      .delete(`organization/${orgid}/template/${templateId}`)
      .then(() => {
        message.success("Template deleted");
        setMode("list");
        loadTemplates();
      })
      .catch((err) => message.error(getErrorMessage(err)));
  };

  return (
    <div>
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/organizations/templates"
        title="Edit template"
        description="Change the name, description or job flow of this template."
      />
      {loading ? (
        <LoadingFallback />
      ) : error ? (
        <Alert title="Could not load the template" description={error} type="error" showIcon />
      ) : template ? (
        <>
          <SettingsForm<EditTemplateForm>
            className="editor-form"
            name="edit-template"
            initialValues={{ name: template.attributes.name, description: template.attributes.description }}
            onFinish={onFinish}
            saveLabel="Update template"
            saveDisabled={!managePermission}
          >
            <div className="editor-form-fields">
              <SettingsSection title="Identity">
                <IdField value={template.id} copiedMessage="Template ID copied" />
                <Form.Item
                  name="name"
                  label="Name"
                  extra="Shown in the workspace when someone starts a job."
                  rules={[{ required: true, message: "Enter a name for the template" }]}
                >
                  <Input />
                </Form.Item>
                <Form.Item name="description" label="Description">
                  <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} />
                </Form.Item>
              </SettingsSection>
            </div>
            <SettingsSection
              maxWidth="100%"
              title="Definition"
              description="The YAML flow this template runs, before or after plan, apply and destroy."
            >
              <CodeEditor height="45vh" onMount={handleEditorDidMount} defaultLanguage="yaml" defaultValue={tcl} />
            </SettingsSection>
          </SettingsForm>
          <DangerZone
            actionName="Delete this template"
            description="Workspaces can no longer run jobs with this template. Notifications filtered only to this template then fire for every template. This cannot be undone."
            disabled={!managePermission}
            onConfirm={onDelete}
            confirmMessage={`Workspaces can no longer run jobs with ${template.attributes.name}. Notifications filtered only to this template then fire for every template. This cannot be undone.`}
          />
        </>
      ) : (
        <Alert title="Could not load the template" type="error" showIcon />
      )}
    </div>
  );
};
