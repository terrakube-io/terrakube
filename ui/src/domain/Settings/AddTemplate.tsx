import type { OnMount } from "@monaco-editor/react";
import { CodeEditor } from "@/components/forms/CodeEditor";
import { Button, Card, Form, Input, List, message, Typography } from "antd";
import { Buffer } from "buffer";
import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";
import { TemplateAttributes } from "../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import "./EditorForm.css";
import "./AddTemplate.css";
import { PermissionErrorMessage } from "@/components/feedback/PermissionErrorMessage";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
const { Meta } = Card;

type Props = {
  setMode: (mode: string) => void;
  loadTemplates: () => void;
};

type IStandaloneCodeEditor = Parameters<OnMount>[0];

type AddTemplateForm = {
  name: string;
  description?: string;
  tcl: string;
  version: string;
};
export const AddTemplate = ({ setMode, loadTemplates }: Props) => {
  const { orgid } = useParams();
  const [chosen, setChosen] = useState(false);
  const [tcl, setTCL] = useState("");
  const [templates, setTemplates] = useState<TemplateAttributes[]>([]);
  const editorRef = useRef<IStandaloneCodeEditor>(null);

  function handleEditorDidMount(editor: IStandaloneCodeEditor) {
    editorRef.current = editor;
  }

  const handleClick = (item: TemplateAttributes) => {
    const buff = Buffer.from(item.tcl, "base64");
    setTCL(buff.toString("ascii"));
    setChosen(true);
  };

  useEffect(() => {
    getTCLTemplates();
  }, [orgid]);

  const getTCLTemplates = () => {
    //TODO: Use github repo to get Templates
    const templates: TemplateAttributes[] = [
      {
        name: "Blank template",
        description: "Create an empty template. So you can define your template from scratch.",
        tcl: "ZmxvdzoKICAtIHR5cGU6ICJ0ZXJyYWZvcm1QbGFuIgogICAgbmFtZTogIlBsYW4iCiAgICBzdGVwOiAxMDAKICAtIHR5cGU6ICJ0ZXJyYWZvcm1BcHBseSIKICAgIG5hbWU6ICJBcHBseSIKICAgIHN0ZXA6IDIwMA==",
        image: "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1d/No_image.svg/2048px-No_image.svg.png",
      },
      {
        name: "Snyk",
        description: "This template uses Snyk to scan and monitor your workspace for security vulnerabilities.",
        tcl: "ZmxvdzoKICAtIHR5cGU6ICJjdXN0b21TY3JpcHRzIgogICAgc3RlcDogMTAwCiAgICBjb21tYW5kczoKICAgICAgLSBydW50aW1lOiAiR1JPT1ZZIgogICAgICAgIHByaW9yaXR5OiAxMDAKICAgICAgICBhZnRlcjogdHJ1ZQogICAgICAgIHNjcmlwdDogfAogICAgICAgICAgaW1wb3J0IFNueWsKCiAgICAgICAgICBuZXcgU255aygpLmxvYWRUb29sKAogICAgICAgICAgICAiJHdvcmtpbmdEaXJlY3RvcnkiLAogICAgICAgICAgICAiJGJhc2hUb29sc0RpcmVjdG9yeSIsCiAgICAgICAgICAgICIxLjgzMS4wIikKICAgICAgICAgICJTbnlrIERvd25sb2FkIENvbXBsZXRlZC4uLiIKICAgICAgLSBydW50aW1lOiAiQkFTSCIKICAgICAgICBwcmlvcml0eTogMjAwCiAgICAgICAgYWZ0ZXI6IHRydWUKICAgICAgICBzY3JpcHQ6IHwKICAgICAgICAgIGNkICR3b3JraW5nRGlyZWN0b3J5OwogICAgICAgICAgc255ayBpYWMgdGVzdCAuOw==",
        image: "https://res.cloudinary.com/snyk/image/upload/v1537345897/press-kit/brand/logo-vertical-black.png",
      },
      {
        name: "Terrascan",
        description: "Terrascan is a static code analyzer for Infrastructure as Code.",
        tcl: "ZmxvdzoKICAtIHR5cGU6ICJjdXN0b21TY3JpcHRzIgogICAgc3RlcDogMTAwCiAgICBjb21tYW5kczoKICAgICAgLSBydW50aW1lOiAiR1JPT1ZZIgogICAgICAgIHByaW9yaXR5OiAxMDAKICAgICAgICBhZnRlcjogdHJ1ZQogICAgICAgIHNjcmlwdDogfAogICAgICAgICAgaW1wb3J0IFRlcnJhc2NhbgoKICAgICAgICAgIG5ldyBUZXJyYXNjYW4oKS5sb2FkVG9vbCgKICAgICAgICAgICAgIiR3b3JraW5nRGlyZWN0b3J5IiwKICAgICAgICAgICAgIiRiYXNoVG9vbHNEaXJlY3RvcnkiLAogICAgICAgICAgICAiMS4xMi4wIikKICAgICAgICAgICJUZXJyYXNjYW4gRG93bmxvYWQgQ29tcGxldGVkLi4uIgogICAgICAtIHJ1bnRpbWU6ICJCQVNIIgogICAgICAgIHByaW9yaXR5OiAyMDAKICAgICAgICBhZnRlcjogdHJ1ZQogICAgICAgIHNjcmlwdDogfAogICAgICAgICAgY2QgJHdvcmtpbmdEaXJlY3Rvcnk7CiAgICAgICAgICB0ZXJyYXNjYW4gc2NhbiAtaSB0ZXJyYWZvcm0gLXQgYXp1cmU7",
        image:
          "https://raw.githubusercontent.com/accurics/terrascan/master/docs/img/Terrascan_By_Accurics_Logo_38B34A-333F48.svg",
      },
      {
        name: "Open Policy Agent",
        description:
          "Whether for one service or for all your services, use OPA to decouple policy from the service's code so you can release, analyze, and review policies without sacrificing availability or performance.",
        tcl: "ZmxvdzoKLSB0eXBlOiAidGVycmFmb3JtUGxhbiIKICBzdGVwOiAxMDAKICBjb21tYW5kczoKICAgIC0gcnVudGltZTogIkdST09WWSIKICAgICAgcHJpb3JpdHk6IDEwMAogICAgICBhZnRlcjogdHJ1ZQogICAgICBzY3JpcHQ6IHwKICAgICAgICBpbXBvcnQgSW5mcmFjb3N0CgogICAgICAgIFN0cmluZyBjcmVkZW50aWFscyA9ICJ2ZXJzaW9uOiBcIjAuMVwiXG4iICsKICAgICAgICAgICAgICAgICJhcGlfa2V5OiAkSU5GUkFDT1NUX0tFWSBcbiIgKwogICAgICAgICAgICAgICAgInByaWNpbmdfYXBpX2VuZHBvaW50OiBodHRwczovL3ByaWNpbmcuYXBpLmluZnJhY29zdC5pbyIKCiAgICAgICAgbmV3IEluZnJhY29zdCgpLmxvYWRUb29sKAogICAgICAgICAgICIkd29ya2luZ0RpcmVjdG9yeSIsCiAgICAgICAgICAgIiRiYXNoVG9vbHNEaXJlY3RvcnkiLCAKICAgICAgICAgICAiMC45LjExIiwKICAgICAgICAgICBjcmVkZW50aWFscykKICAgICAgICAiSW5mcmFjb3N0IERvd25sb2FkIENvbXBsZXRlZC4uLiIKICAgIC0gcnVudGltZTogIkJBU0giCiAgICAgIHByaW9yaXR5OiAyMDAKICAgICAgYWZ0ZXI6IHRydWUKICAgICAgc2NyaXB0OiB8CiAgICAgICAgdGVycmFmb3JtIHNob3cgLWpzb24gdGVycmFmb3JtTGlicmFyeS50ZlBsYW4gPiBwbGFuLmpzb24gCiAgICAgICAgaW5mcmFjb3N0IGJyZWFrZG93biAtLXBhdGggcGxhbi5qc29u",
        image:
          "https://d33wubrfki0l68.cloudfront.net/037435cf0eb3b77f6d9080c7b25c54191490aa8d/c4776/img/logos/opa-horizontal-color.png",
      },
      {
        name: "Terratag",
        description:
          "This template uses Terratag allowing for tags or labels to be applied across an entire set of Terraform files.",
        tcl: "ZmxvdzoKLSB0eXBlOiAidGVycmFmb3JtUGxhbiIKICBzdGVwOiAxMDAKICBjb21tYW5kczoKICAgIC0gcnVudGltZTogIkdST09WWSIKICAgICAgcHJpb3JpdHk6IDEwMAogICAgICBiZWZvcmU6IHRydWUKICAgICAgc2NyaXB0OiB8CiAgICAgICAgaW1wb3J0IFRlcnJhVGFnCiAgICAgICAgbmV3IFRlcnJhVGFnKCkubG9hZFRvb2woCiAgICAgICAgICAiJHdvcmtpbmdEaXJlY3RvcnkiLAogICAgICAgICAgIiRiYXNoVG9vbHNEaXJlY3RvcnkiLAogICAgICAgICAgIjAuMS4zMCIpCiAgICAgICAgIlRlcnJhdGFnIGrvd25sb2FkIGNvbXBsZXRlZCIKICAgIC0gcnVudGltZTogIkJBU0giCiAgICAgIHByaW9yaXR5OiAyMDAKICAgICAgYmVmb3JlOiB0cnVlCiAgICAgIHNjcmlwdDogfAogICAgICAgIGNkICR3b3JraW5nRGlyZWN0b3J5CiAgICAgICAgdGVycmF0YWcgLXRhZ3M9IntcImVudmlyb25tZW50X2lkXCI6IFwiZGV2ZWxvcG1lbnRcIn0iCi0gdHlwZTogInRlcnJhZm9ybUFwcGx5IgogIHN0ZXA6IDMwMA==",
        image: "https://raw.githubusercontent.com/env0/terratag/master/ttlogo.png",
      },
      {
        name: "Infracost",
        description: "This template uses Infracost to show cloud cost estimates for your wokspace resources.",
        tcl: "ZmxvdzoKLSB0eXBlOiAidGVycmFmb3JtUGxhbiIKICBzdGVwOiAxMDAKICBjb21tYW5kczoKICAgIC0gcnVudGltZTogIkdST09WWSIKICAgICAgcHJpb3JpdHk6IDEwMAogICAgICBhZnRlcjogdHJ1ZQogICAgICBzY3JpcHQ6IHwKICAgICAgICBpbXBvcnQgSW5mcmFjb3N0CgogICAgICAgIFN0cmluZyBjcmVkZW50aWFscyA9ICJ2ZXJzaW9uOiBcIjAuMVwiXG4iICsKICAgICAgICAgICAgICAgICJhcGlfa2V5OiAkSU5GUkFDT1NUX0tFWSBcbiIgKwogICAgICAgICAgICAgICAgInByaWNpbmdfYXBpX2VuZHBvaW50OiBodHRwczovL3ByaWNpbmcuYXBpLmluZnJhY29zdC5pbyIKCiAgICAgICAgbmV3IEluZnJhY29zdCgpLmxvYWRUb29sKAogICAgICAgICAgICIkd29ya2luZ0RpcmVjdG9yeSIsCiAgICAgICAgICAgIiRiYXNoVG9vbHNEaXJlY3RvcnkiLCAKICAgICAgICAgICAiMC45LjExIiwKICAgICAgICAgICBjcmVkZW50aWFscykKICAgICAgICAiSW5mcmFjb3N0IERvd25sb2FkIENvbXBsZXRlZC4uLiIKICAgIC0gcnVudGltZTogIkJBU0giCiAgICAgIHByaW9yaXR5OiAyMDAKICAgICAgYWZ0ZXI6IHRydWUKICAgICAgc2NyaXB0OiB8CiAgICAgICAgdGVycmFmb3JtIHNob3cgLWpzb24gdGVycmFmb3JtTGlicmFyeS50ZlBsYW4gPiBwbGFuLmpzb24gCiAgICAgICAgaW5mcmFjb3N0IGJyZWFrZG93biAtLXBhdGggcGxhbi5qc29u",
        image: "https://raw.githubusercontent.com/infracost/infracost/master/.github/assets/logo.svg",
      },
    ];

    setTemplates(templates);
  };

  const onFinish = (values: AddTemplateForm) => {
    const body = {
      data: {
        type: "template",
        attributes: {
          name: values.name,
          description: values.description,
          tcl: Buffer.from(editorRef.current ? editorRef.current.getValue() : tcl).toString("base64"),
          version: "1.0.0",
        },
      },
    };

    axiosInstance
      .post(`organization/${orgid}/template`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status == 201) {
          message.success("Template created successfully");
          loadTemplates();
          setMode("list");
        }
      })
      .catch((error) => {
        if (error.response) {
          if (error.response.status === 403) {
            message.error(<PermissionErrorMessage action="create Templates" permission="Manage Templates" />);
          }
        }
      });
  };
  return (
    <div>
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/organizations/templates"
        title="Create template"
        description="A template is a job flow: the steps a run executes before or after plan, apply and destroy."
      />
      {!chosen ? (
        <SettingsSection
          maxWidth="100%"
          title="Start from"
          description="Start from a blank template or from one of the built-in tool integrations."
        >
          <List
            grid={{ gutter: 24, column: 3, xs: 1, sm: 2, md: 2, lg: 3, xl: 3, xxl: 4 }}
            dataSource={templates}
            renderItem={(item) => (
              <List.Item>
                <Card
                  hoverable
                  className="template-card template-card-flat"
                  role="button"
                  tabIndex={0}
                  aria-label={`Start from ${item.name}`}
                  onClick={() => handleClick(item)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      handleClick(item);
                    }
                  }}
                  cover={
                    <div className="template-card-cover">
                      <img alt="" src={item.image} />
                    </div>
                  }
                >
                  <Meta
                    title={item.name}
                    description={
                      <Typography.Paragraph
                        type="secondary"
                        ellipsis={{ rows: 3 }}
                        className="template-card-description"
                      >
                        {item.description}
                      </Typography.Paragraph>
                    }
                  />
                </Card>
              </List.Item>
            )}
          />
        </SettingsSection>
      ) : (
        <SettingsForm<AddTemplateForm>
          className="editor-form"
          name="create-template"
          onFinish={onFinish}
          saveLabel="Create template"
        >
          <div className="editor-form-fields">
            <SettingsSection title="Identity">
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
            description="The YAML flow this template runs."
            extra={
              <Button type="link" onClick={() => setChosen(false)}>
                Choose another starting point
              </Button>
            }
          >
            <CodeEditor height="45vh" onMount={handleEditorDidMount} defaultLanguage="yaml" defaultValue={tcl} />
          </SettingsSection>
        </SettingsForm>
      )}
    </div>
  );
};
