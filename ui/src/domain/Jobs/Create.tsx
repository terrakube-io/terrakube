import { DeleteOutlined, PlayCircleOutlined } from "@ant-design/icons";
import { Button, Collapse, Form, Input, Select, Tooltip, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { Resource, Template } from "../types";
import { buildResourceOptions } from "../Workspaces/workspaceDataUtils";
import LoadingFallback from "@/components/feedback/LoadingFallback";
import { CrudFormModal } from "@/components/modals/CrudFormModal";
import { RadioChoices } from "@/components/settings/RadioChoices";
import "./runTokens.css";
import "./Create.css";

const validateMessages = { required: "${label} is required" };

const isDestroyTemplate = (template: Template) => template.attributes.name.includes("Destroy");

const templateLabel = (template: Template) =>
  isDestroyTemplate(template) ? (
    <span className="run-template--destroy">
      <DeleteOutlined aria-hidden /> {template.attributes.name}
    </span>
  ) : (
    template.attributes.name
  );

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
        message.error("Could not start the run: " + (error?.response?.data?.errors?.[0]?.detail ?? error.message));
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

      <CrudFormModal<CreateJobForm>
        open={visible}
        title="Start a run"
        okText="Start run"
        form={form}
        formName="create-run"
        validateMessages={validateMessages}
        onCancel={onCancel}
        onSubmit={(values) => {
          form.resetFields();
          onCreate(values);
        }}
      >
        <Form.Item name="templateId" label="Template" rules={[{ required: true }]} initialValue={defaultTemplate}>
          {loading || !templates ? (
            <LoadingFallback />
          ) : templates.length <= 3 ? (
            <RadioChoices
              options={templates.map((item) => ({
                value: item.id,
                label: templateLabel(item),
                help: isDestroyTemplate(item)
                  ? "Destroys every resource this workspace manages."
                  : item.attributes.description,
              }))}
            />
          ) : (
            <Select options={templates.map((item) => ({ value: item.id, label: templateLabel(item) }))} />
          )}
        </Form.Item>
        <Form.Item
          name="branchName"
          label="Branch"
          extra="The run uses this branch instead of the workspace default. Leave it as is for CLI-driven workspaces."
          initialValue={branchName}
        >
          <Input />
        </Form.Item>
        <Collapse
          ghost
          className="run-create-options"
          items={[
            {
              key: "additionalPlanningOptions",
              label: "Additional planning options",
              children: (
                <>
                  <Form.Item
                    name="targetAddrs"
                    label="Target resources"
                    extra="Only these resources and their dependencies are planned. Type an address and press Enter to add it."
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
                    extra="These resources are destroyed and created again on apply, even if nothing changed."
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
      </CrudFormModal>
    </div>
  );
};
