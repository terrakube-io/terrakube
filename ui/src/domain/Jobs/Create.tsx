import { DeleteOutlined, InfoCircleOutlined, PlayCircleOutlined } from "@ant-design/icons";
import { Button, Collapse, Form, Input, Modal, Select, Space, Tooltip, message, Typography } from "antd";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { Resource, Template } from "../types";
import { buildResourceOptions } from "../Workspaces/workspaceDataUtils";

const validateMessages = { required: "${label} is required!" };

type Props = {
  changeJob: (id: string) => void;
  planJob?: boolean;
  // When set, "Run now" is disabled and this message explains why (e.g. a CLI/API
  // workspace that has no applied configuration to re-run yet).
  disabledReason?: string;
  // The workspace's current state resources, offered as selectable options for
  // Target/Replace resources below (in addition to freely typing an address).
  resources?: Resource[];
};

type CreateJobForm = {
  templateId: string;
  branchName: string;
  targetAddrs?: string[];
  replaceAddrs?: string[];
};

export const CreateJob = ({ changeJob, planJob = true, disabledReason, resources }: Props) => {
  const navigate = useNavigate();
  const workspaceId = sessionStorage.getItem(WORKSPACE_ARCHIVE);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [visible, setVisible] = useState(false);
  const [form] = Form.useForm<CreateJobForm>();
  const [defaultTemplate, setDefaultTemplate] = useState();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [branchName, setBranchName] = useState([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const resourceOptions = useMemo(() => buildResourceOptions(resources ?? []), [resources]);

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
      const { branch, defaultTemplate } = response.data.data.attributes;
      setDefaultTemplate(defaultTemplate);
      setBranchName(branch);
      form.setFieldsValue({ templateId: defaultTemplate, branchName: branch });
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
    // Close modal immediately — don't make user wait
    setVisible(false);
    setSubmitting(true);

    const body = {
      data: {
        type: "job",
        attributes: {
          templateReference: values.templateId,
          overrideBranch: values.branchName,
          via: "UI",
          targetAddrs: values.targetAddrs?.length ? values.targetAddrs : undefined,
          replaceAddrs: values.replaceAddrs?.length ? values.replaceAddrs : undefined,
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
        const newJobId = response.data.data.id;
        setSubmitting(false);
        changeJob(newJobId);

        if (organizationId && workspaceId) {
          navigate(`/organizations/${organizationId}/workspaces/${workspaceId}/runs/${newJobId}`);
        }
      })
      .catch((error) => {
        setSubmitting(false);
        message.error("Failed to start job: " + error.response.data.errors[0].detail);
      });
  };

  return (
    <div>
      <Tooltip title={disabledReason}>
        <Button
          type="primary"
          htmlType="button"
          onClick={() => {
            loadBranch();
            setVisible(true);
          }}
          icon={<PlayCircleOutlined />}
          disabled={!planJob || submitting || !!disabledReason}
          loading={submitting}
        >
          Run now
        </Button>
      </Tooltip>

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
            .catch(() => {});
        }}
      >
        <Space direction="vertical">
          <div>
            <InfoCircleOutlined style={{ fontSize: "16px", marginRight: "8px", color: "var(--tk-accent)" }} />
            <Typography.Text type="secondary">
              You will be redirected to the run details page to see this job executed.
            </Typography.Text>
          </div>
          <Form form={form} layout="vertical" name="create-org" validateMessages={validateMessages}>
            <Form.Item
              name="templateId"
              label="Choose job type"
              rules={[{ required: true }]}
              initialValue={defaultTemplate}
            >
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
            <Collapse
              ghost
              items={[
                {
                  key: "additionalPlanningOptions",
                  label: "Additional planning options",
                  children: (
                    <>
                      <Form.Item
                        name="targetAddrs"
                        label="Target resources"
                        tooltip="Limit the plan to these resource addresses and their dependencies, e.g. aws_instance.example or module.foo.aws_instance.bar. Type an address and press Enter to add it."
                      >
                        <Select
                          mode="tags"
                          tokenSeparators={[","]}
                          placeholder="Select or type a resource address"
                          options={resourceOptions}
                        />
                      </Form.Item>
                      <Form.Item
                        name="replaceAddrs"
                        label="Replace resources"
                        tooltip="Force replacement of these resource addresses on the next apply, e.g. aws_instance.example or module.foo.aws_instance.bar. Type an address and press Enter to add it."
                      >
                        <Select
                          mode="tags"
                          tokenSeparators={[","]}
                          placeholder="Select or type a resource address"
                          options={resourceOptions}
                        />
                      </Form.Item>
                    </>
                  ),
                },
              ]}
            />
          </Form>
        </Space>
      </Modal>
    </div>
  );
};
