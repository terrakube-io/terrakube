import { Button, Col, Flex, Form, Input, message, Radio, Row, Space, Typography, Spin, ColorPicker } from "antd";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Organization, sparseFields, SparseOf } from "../types";
import { IconSelector } from "../Organizations/IconSelector";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { organizationNameRules } from "../../config/validation";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";

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
    <div className="setting">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/organizations"
        title="General Settings"
        description="Configure general settings for your organization."
      />
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : loading || organization === undefined ? (
        <Spin />
      ) : (
        <Spin spinning={waiting}>
          <Form
            layout="vertical"
            name="form-settings"
            requiredMark={false}
            onFinish={onFinish}
            initialValues={{
              name: organization.attributes.name,
              description: organization.attributes.description,
              executionMode: organization.attributes.executionMode,
            }}
          >
            <SettingsSection
              maxWidth={960}
              title="Identity"
              description="Basic information about this organization, and the icon and color shown for it throughout the app."
            >
              <Form.Item name="name" label="Name" rules={organizationNameRules}>
                <Input />
              </Form.Item>
              <Form.Item name="description" label="Description" tooltip="A brief description of this organization.">
                <Input.TextArea rows={3} autoSize={{ minRows: 2, maxRows: 4 }} />
              </Form.Item>
              <Form.Item label="Icon and Color">
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
            </SettingsSection>

            <SettingsSection
              maxWidth={960}
              title="Execution Mode"
              description="The default execution mode suggested to new workspaces created in this organization. This is informational only and does not affect existing workspaces."
            >
              <Form.Item name="executionMode" label="Default Execution Mode for New Workspaces">
                <Radio.Group style={{ width: "100%" }}>
                  <Row gutter={16}>
                    <Col xs={24} md={12}>
                      <Radio value="remote" className="execution-mode-option">
                        <b>Remote</b>
                        <Typography.Text type="secondary" style={{ display: "block" }}>
                          Terrakube hosts your plans and applies, allowing you and your team to collaborate and review
                          jobs in the app.
                        </Typography.Text>
                      </Radio>
                    </Col>
                    <Col xs={24} md={12}>
                      <Radio value="local" className="execution-mode-option">
                        <b>Local</b>
                        <Typography.Text type="secondary" style={{ display: "block" }}>
                          Your planning and applying jobs are performed on your own machines. Terrakube is used just for
                          storing and syncing the state.
                        </Typography.Text>
                      </Radio>
                    </Col>
                  </Row>
                </Radio.Group>
              </Form.Item>
            </SettingsSection>

            <Form.Item style={{ maxWidth: 960 }}>
              <Flex justify="flex-end">
                <Button type="primary" htmlType="submit" disabled={!managePermission}>
                  Update organization
                </Button>
              </Flex>
            </Form.Item>
          </Form>
        </Spin>
      )}
      <SettingsSection
        danger
        title="Delete this Organization"
        description={
          <>
            Deleting the <strong>{organization?.attributes?.name}</strong> organization will permanently delete all
            workspaces associated with it.
            <br />
            Please be certain that you understand this. This action cannot be undone.
          </>
        }
      >
        <Button type="primary" danger disabled={!managePermission} onClick={() => setDeleteModalOpen(true)}>
          Delete this organization
        </Button>
      </SettingsSection>
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
    </div>
  );
};
