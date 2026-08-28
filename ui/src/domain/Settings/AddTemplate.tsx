import { Editor, type OnMount, type OnValidate } from "@monaco-editor/react";
import { InfoCircleOutlined } from "@ant-design/icons";
import { Button, Card, Col, Flex, Form, Input, List, message, Row, Space, Steps, Typography, theme } from "antd";
import { Buffer } from "buffer";
import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";
import { getMonacoTheme, monacoOptions } from "../../config/monacoConfig";
import { TemplateAttributes } from "../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import { PermissionErrorMessage } from "@/components/feedback/PermissionErrorMessage";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
const { Meta } = Card;
const validateMessages = {
  required: "${label} is required!",
};

type Props = {
  setMode: (mode: string) => void;
  loadTemplates: () => void;
};

type IStandaloneCodeEditor = Parameters<OnMount>[0];
type IMarkerArray = Parameters<OnValidate>[0];

type AddTemplateForm = {
  name: string;
  description?: string;
  tcl: string;
  version: string;
};
export const AddTemplate = ({ setMode, loadTemplates }: Props) => {
  const { orgid } = useParams();
  const [current, setCurrent] = useState(0);
  const [tcl, setTCL] = useState("");
  const [templates, setTemplates] = useState<TemplateAttributes[]>([]);
  const editorRef = useRef<IStandaloneCodeEditor>(null);
  const { token } = theme.useToken();

  function handleEditorDidMount(editor: IStandaloneCodeEditor) {
    editorRef.current = editor;
  }

  const handleChange = (currentVal: number) => {
    if (current === 1 && editorRef.current) {
      setTCL(editorRef.current.getValue());
    }
    setCurrent(currentVal);
  };
  const handleClick = (item: TemplateAttributes) => {
    const buff = Buffer.from(item.tcl, "base64");
    setTCL(buff.toString("ascii"));
    setCurrent(1);
  };

  useEffect(() => {
    getTCLTemplates();
  }, [orgid]);

  const getTCLTemplates = () => {
    //TODO: Use github repo to get Templates
    const templates: TemplateAttributes[] = [
      {
        name: "Blank Template",
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

  const handleContinue = () => {
    setCurrent(2);
    setTCL(editorRef.current.getValue());
  };

  function handleEditorValidation(markers: IMarkerArray) {
    markers.forEach((marker) => console.log("onValidate:", marker.message));
  }

  const onFinish = (values: AddTemplateForm) => {
    const body = {
      data: {
        type: "template",
        attributes: {
          name: values.name,
          description: values.description,
          tcl: Buffer.from(tcl).toString("base64"),
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
        title="Create a new Template"
        description="Templates allow you to define a custom flow so you can run any tool before or after terraform plan/apply/destroy."
      />
      <Steps
        direction="horizontal"
        size="small"
        current={current}
        onChange={handleChange}
        style={{ maxWidth: 960, margin: "8px 0 32px" }}
        items={[
          { title: "Choose Type", description: "Pick a starting point" },
          { title: "Define Template", description: "Write the job flow" },
          { title: "Configure Settings", description: "Name and create" },
        ]}
      />
      {current == 0 && (
        <SettingsSection
          maxWidth="100%"
          title="Choose your template"
          description="Start from a blank template or from one of the built-in tool integrations."
        >
          <List
            grid={{ gutter: 24, column: 3, xs: 1, sm: 2, md: 2, lg: 3, xl: 3, xxl: 4 }}
            dataSource={templates}
            renderItem={(item) => (
              <List.Item>
                <Card
                  hoverable
                  className="template-card"
                  onClick={() => handleClick(item)}
                  cover={
                    <div className="template-card-cover">
                      <img alt={item.name} src={item.image} />
                    </div>
                  }
                >
                  <Meta
                    title={item.name}
                    description={
                      <Typography.Paragraph type="secondary" ellipsis={{ rows: 3 }} style={{ marginBottom: 0 }}>
                        {item.description}
                      </Typography.Paragraph>
                    }
                  />
                </Card>
              </List.Item>
            )}
          />
        </SettingsSection>
      )}
      {current == 1 && (
        <>
          <SettingsSection
            maxWidth={960}
            title="Set up template"
            description="Define the YAML flow this template runs."
          >
            <div className="editor">
              <Editor
                height="45vh"
                onMount={handleEditorDidMount}
                onValidate={handleEditorValidation}
                defaultLanguage="yaml"
                defaultValue={tcl}
                theme={getMonacoTheme(token.colorBgContainer === "#141414" ? "dark" : "light")}
                options={monacoOptions}
              />
            </div>
          </SettingsSection>
          <Flex justify="flex-end" style={{ maxWidth: 960 }}>
            <Space>
              <Button onClick={() => handleChange(0)}>Back</Button>
              <Button type="primary" onClick={handleContinue} htmlType="button">
                Continue
              </Button>
            </Space>
          </Flex>
        </>
      )}
      {current == 2 && (
        <Form onFinish={onFinish} validateMessages={validateMessages} name="create-template" layout="vertical">
          <SettingsSection maxWidth={960} title="Configure settings">
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item
                  name="name"
                  label="Name"
                  tooltip={{
                    title: "A name for your Template. This will appear in the workspaces when you execute a new job.",
                    icon: <InfoCircleOutlined />,
                  }}
                  rules={[{ required: true }]}
                >
                  <Input />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="description" label="Description" style={{ marginBottom: 0 }}>
              <Input.TextArea rows={2} />
            </Form.Item>
          </SettingsSection>
          <Flex justify="flex-end" style={{ maxWidth: 960 }}>
            <Space>
              <Button onClick={() => setCurrent(1)}>Back</Button>
              <Button type="primary" htmlType="submit">
                Create Template
              </Button>
            </Space>
          </Flex>
        </Form>
      )}
    </div>
  );
};
