import { Button, Form, Input, message, Radio, Space, Typography, ColorPicker } from "antd";
import { Loading } from "@/components/feedback/Loading";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Organization, sparseFields, SparseOf } from "../types";
import { IconSelector } from "../Organizations/IconSelector";
import { organizationNameRules } from "../../config/validation";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { useOrganizationSummaries } from "@/modules/organizations/useOrganizationSummaries";

const DEFAULT_ICON = "FaBuilding";
const DEFAULT_COLOR = "#000000";

const ORGANIZATION_FIELDS = sparseFields<Organization>("organization")("name", "description", "executionMode", "icon");
type SparseOrganization = SparseOf<typeof ORGANIZATION_FIELDS>;

type GeneralSettingsForm = {
  name: string;
  description: string;
  executionMode: "remote" | "local";
  icon?: string;
};

type Props = {
  managePermission?: boolean;
};

export const GeneralSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams();
  const { removeOrganization, updateOrganization } = useOrganizationSummaries();
  const [organization, setOrganization] = useState<SparseOrganization>();
  const [loading, setLoading] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [error, setError] = useState<string>();
  const [form] = Form.useForm();
  const [icon, setIcon] = useState<string>(DEFAULT_ICON);
  const [color, setColor] = useState<string>(DEFAULT_COLOR);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);

  const onFinish = (values: GeneralSettingsForm) => {
    setWaiting(true);
    const iconField = icon ? `${icon}:${color}` : undefined;
    const body = {
      data: {
        type: "organization",
        id: orgid,
        attributes: {
          name: values.name,
          description: values.description,
          executionMode: values.executionMode,
          icon: iconField,
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status == 204) {
          message.success("Organization updated successfully");
          if (orgid) {
            updateOrganization(orgid, { ...values, icon: iconField });
          }
        } else {
          message.error("Organization update failed");
        }
        setWaiting(false);
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
        setWaiting(false);
      });
  };

  const onDelete = () => {
    const body = {
      data: {
        type: "organization",
        id: orgid,
        attributes: {
          disabled: "true",
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status == 204) {
          message.success("Organization deleted successfully, please logout and login to Terrakube");
          if (orgid) {
            removeOrganization(orgid);
          }
        } else {
          message.error("Organization deletion failed");
        }
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  useEffect(() => {
    setLoading(true);
    axiosInstance
      .get(`organization/${orgid}?${ORGANIZATION_FIELDS}`)
      .then((response) => {
        setOrganization(response.data.data);
        const iconField = response.data.data.attributes.icon;
        if (iconField) {
          const [iconName, iconColor] = iconField.split(":");
          setIcon(iconName || DEFAULT_ICON);
          setColor(iconColor || DEFAULT_COLOR);
        } else {
          setIcon(DEFAULT_ICON);
          setColor(DEFAULT_COLOR);
        }
        form.setFieldsValue({
          name: response.data.data.attributes.name,
          description: response.data.data.attributes.description,
          executionMode: response.data.data.attributes.executionMode,
        });
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error("Failed to load organization settings");
        }
        setLoading(false);
      });
  }, [orgid, form]);

  return (
    <div className="setting general-settings">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/organizations"
        title="General settings"
        divider={false}
      />
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : (
        <Loading loading={loading || organization === undefined} description="Loading organization settings...">
          <Loading overlay loading={waiting}>
            <div className="general-settings-column">
              <div className="general-settings-id">
                <Typography.Text strong>ID</Typography.Text>
                <Typography.Text copyable={{ tooltips: ["Copy ID", "Copied"] }} className="general-settings-id-value">
                  {orgid}
                </Typography.Text>
              </div>

              <Form
                form={form}
                layout="vertical"
                name="form-settings"
                requiredMark={false}
                onFinish={onFinish}
                initialValues={{
                  name: organization?.attributes.name,
                  description: organization?.attributes.description,
                  executionMode: organization?.attributes.executionMode,
                }}
              >
                <Form.Item name="name" label="Name" rules={organizationNameRules}>
                  <Input />
                </Form.Item>
                <Form.Item name="description" label="Description">
                  <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} />
                </Form.Item>
                <Form.Item label="Icon and color" extra="Shown for this organization throughout Terrakube.">
                  <Space align="start">
                    <IconSelector value={icon} color={color} onChange={setIcon} />
                    <ColorPicker
                      value={color}
                      onChange={(colorObj) => setColor(colorObj.toHexString())}
                      presets={[
                        {
                          label: "Recommended",
                          colors: ["#000000", "#1890ff", "#722ED1", "#2eb039", "#fa8f37", "#FB0136"],
                        },
                      ]}
                    />
                  </Space>
                </Form.Item>

                <Typography.Title level={5} className="general-settings-subtitle">
                  Organizational default execution mode
                </Typography.Title>
                <Typography.Paragraph type="secondary">
                  Suggested to new workspaces created in this organization. Existing workspaces keep their mode.
                </Typography.Paragraph>
                <Form.Item name="executionMode" label="Workspaces">
                  <Radio.Group className="general-settings-modes">
                    <Radio value="remote">
                      Remote
                      <span className="general-settings-mode-help">
                        Terrakube hosts your plans and applies, allowing you and your team to collaborate and review
                        jobs in the app.
                      </span>
                    </Radio>
                    <Radio value="local">
                      Local
                      <span className="general-settings-mode-help">
                        Your planning and applying jobs are performed on your own machines. Terrakube is used just for
                        storing and syncing the state.
                      </span>
                    </Radio>
                  </Radio.Group>
                </Form.Item>

                <Button type="primary" htmlType="submit" disabled={!managePermission}>
                  Update organization
                </Button>
              </Form>

              <section className="general-settings-danger">
                <Typography.Title level={3}>Destruction and deletion</Typography.Title>
                <Typography.Text strong>Delete this organization</Typography.Text>
                <Typography.Paragraph type="secondary">
                  Deleting the {organization?.attributes?.name} organization will permanently delete all workspaces
                  associated with it. Please be certain that you understand this. This action cannot be undone.
                </Typography.Paragraph>
                <Button type="primary" danger disabled={!managePermission} onClick={() => setDeleteModalOpen(true)}>
                  Delete this organization
                </Button>
              </section>
            </div>
            <DeleteConfirmationModal
              open={deleteModalOpen}
              title="Delete this organization"
              message="The organization will be permanently deleted and all its workspaces will be marked as deleted. This action cannot be undone."
              confirmValue={organization?.attributes?.name ?? ""}
              okText="Delete this organization"
              onConfirm={() => {
                onDelete();
                setDeleteModalOpen(false);
              }}
              onCancel={() => setDeleteModalOpen(false)}
            />
          </Loading>
        </Loading>
      )}
    </div>
  );
};
