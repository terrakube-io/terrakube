import { Button, Form, Input, message, Popconfirm, Radio, Space, Typography, Spin, ColorPicker } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Organization, sparseFields, SparseOf } from "../types";
import { IconSelector } from "../Organizations/IconSelector";
import SettingsSection from "@/modules/layout/SettingsSection/SettingsSection";
import { organizationNameRules } from "../../config/validation";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/AccessDeniedAlert";
import { SettingsPageHeader } from "@/modules/layout/SettingsPageHeader";

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
      <SettingsPageHeader title="General Settings" description="Configure general settings for your organization." />
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : loading || organization === undefined ? (
        <Spin tip="Loading Organization Settings..." />
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
            <SettingsSection title="Identity" description="Basic information about this organization.">
              <Form.Item name="name" label="Name" rules={organizationNameRules}>
                <Input />
              </Form.Item>
              <Form.Item
                name="description"
                label="Description"
                extra={<Typography.Text type="secondary">A brief description of this organization.</Typography.Text>}
              >
                <Input.TextArea rows={3} />
              </Form.Item>
            </SettingsSection>

            <SettingsSection
              title="Execution Mode"
              description="The default execution mode suggested to new workspaces created in this organization. This is informational only and does not affect existing workspaces."
            >
              <Form.Item name="executionMode" label="Default Execution Mode for New Workspaces">
                <Radio.Group>
                  <Space direction="vertical">
                    <Radio value="remote">
                      <b>Remote</b>
                      <Typography.Text type="secondary" style={{ display: "block" }}>
                        Terrakube hosts your plans and applies, allowing you and your team to collaborate and review
                        jobs in the app.
                      </Typography.Text>
                    </Radio>
                    <Radio value="local">
                      <b>Local</b>
                      <Typography.Text type="secondary" style={{ display: "block" }}>
                        Your planning and applying jobs are performed on your own machines. Terrakube is used just for
                        storing and syncing the state.
                      </Typography.Text>
                    </Radio>
                  </Space>
                </Radio.Group>
              </Form.Item>
            </SettingsSection>

            <SettingsSection
              title="Appearance"
              description="The icon and color shown for this organization throughout the app."
            >
              <Form.Item label="Organization Icon and Color">
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

            <Form.Item>
              <Button type="primary" htmlType="submit" disabled={!managePermission}>
                Update organization
              </Button>
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
        <Popconfirm
          okButtonProps={{ danger: true }}
          onConfirm={() => {
            onDelete();
          }}
          style={{ width: "100%" }}
          title={
            <p>
              Organization will be permanently deleted and all workspaces will be marked as deleted <br />
              <br />
              Are you sure?
            </p>
          }
          okText="Yes"
          cancelText="No"
          placement="bottom"
        >
          <Button
            type="primary"
            danger
            style={{ width: "fit-content", padding: "8px 24px", height: "auto" }}
            disabled={!managePermission}
          >
            Delete this organization
          </Button>
        </Popconfirm>
      </SettingsSection>
    </div>
  );
};
