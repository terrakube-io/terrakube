import { DeleteOutlined, InfoCircleOutlined, PlayCircleOutlined } from "@ant-design/icons";
import { Button, Form, Input, Modal, Select, Space, message, Typography } from "antd";
import { useEffect, useState } from "react";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { Template } from "../types";

const validateMessages = { required: "${label} is required!" };

type Props = {
  changeJob: (id: string) => void;
};

type CreateJobForm = {
  templateId: string;
  branchName: string;
};

export const CreateJob = ({ changeJob }: Props) => {
  const workspaceId = sessionStorage.getItem(WORKSPACE_ARCHIVE);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [visible, setVisible] = useState(false);
  const [form] = Form.useForm<CreateJobForm>();
  const [defaultTemplate, setDefaultTemplate] = useState();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [branchName, setBranchName] = useState([]);
  const [loading, setLoading] = useState(false);
  const onCancel = () => {
    setVisible(false);
  };

  useEffect(() => {
    setLoading(true);
    loadTemplates();
    loadBranch();
  }, [organizationId]);

  const loadBranch = () => {
    axiosInstance.get(`organization/${organizationId}/workspace/${workspaceId}`).then((response) => {
      console.log(response.data);
      const { branch, defaultTemplate } = response.data.data.attributes;
      setDefaultTemplate(defaultTemplate);
      setBranchName(branch);
    });
  };

  const loadTemplates = () => {
    axiosInstance.get(`organization/${organizationId}/template`).then((response) => {
      const templatesList = response.data.data.filter(function (obj: Template) {
        //exclude CLI based templates
        return (
          obj.attributes.name !== "Terraform-Plan/Apply-Cli" && obj.attributes.name !== "Terraform-Plan/Destroy-Cli"
        );
      });
      setTemplates(templatesList);
      setLoading(false);
    });
  };

  const onCreate = (values: CreateJobForm) => {
    const body = {
      data: {
        type: "job",
        attributes: {
          templateReference: values.templateId,
          overrideBranch: values.branchName,
          via: "UI",
        },
        relationships: {
          workspace: {
            data: {
              type: "workspace",
              id: workspaceId,
            },
          },
        },
      },
    };

    axiosInstance
      .post(`organization/${organizationId}/job`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        setVisible(false);
        changeJob(response.data.data.id);
      })
      .catch((error) => {
        message.error("Not able to create job: " + error.response.data.errors[0].detail);
        setVisible(false);
        console.log(error);
      });
  };

  return (
    <div>
      <Button
        type="primary"
        htmlType="button"
        onClick={() => {
          setVisible(true);
        }}
        icon={<PlayCircleOutlined />}
      >
        Run now
      </Button>

      <Modal
        open={visible}
        title="Run job"
        okText="Start"
        cancelText="Cancel"
        onCancel={onCancel}
        onOk={() => {
          form
            .validateFields()
            .then((values) => {
              form.resetFields();
              onCreate(values);
            })
            .catch((info) => {
              console.log("Validate Failed:", info);
            });
        }}
      >
        <Space direction="vertical">
          <div>
            <InfoCircleOutlined style={{ fontSize: "16px", marginRight: "8px", color: "#1677ff" }} />
            <Typography.Text type="secondary">
              You will be redirected to the run details page to see this job executed.
            </Typography.Text>
          </div>
          <Form form={form} layout="vertical" name="create-org" validateMessages={validateMessages}>
            <Form.Item
              name="templateId"
              label="Choose job type"
              rules={[{ required: true }]}
              initialValue={defaultTemplate}>
              {loading || !templates ? (
                <p>Data loading...</p>
              ) : (
                <Select>
                  {templates.map((item) => (
                    <Select.Option key={item.id} value={item.id}>
                      <span style={item.attributes.name.includes("Destroy") ? { color: "red" } : {}}>
                        {item.attributes.name.includes("Destroy") && <DeleteOutlined style={{ marginRight: 8 }} />}
                        {item.attributes.name}
                      </span>
                    </Select.Option>
                  ))}
                </Select>
              )}
            </Form.Item>
            <Form.Item
              name="branchName"
              label="Branch Name"
              tooltip="Select the branch to use for this job. When using the CLI driven workflow do not modify the branch name."
              initialValue={branchName}
            >
              <Input />
            </Form.Item>
          </Form>
        </Space>
      </Modal>
    </div>
  );
};
