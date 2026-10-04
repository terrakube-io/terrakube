import { Form, Input, message, Space, Typography, ColorPicker } from "antd";
import { Loading } from "@/components/feedback/Loading";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Organization, sparseFields, SparseOf } from "../types";
import { IconSelector } from "../Organizations/IconSelector";
import { organizationNameRules } from "../../config/validation";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { RadioChoices } from "@/components/settings/RadioChoices";
import { IdField } from "@/components/settings/IdField";
import { DangerZone } from "@/components/settings/DangerZone";
import { useOrganizationSummaries } from "@/modules/organizations/useOrganizationSummaries";
import { cacheOrganizationName } from "@/hooks/useOrganizationName";

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
            cacheOrganizationName(orgid, values.name);
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
    <div className="setting">
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
            <SettingsForm
              form={form}
              name="form-settings"
              onFinish={onFinish}
              initialValues={{
                name: organization?.attributes.name,
                description: organization?.attributes.description,
                executionMode: organization?.attributes.executionMode,
              }}
              saveLabel="Update organization"
              saveDisabled={!managePermission}
            >
              <IdField id="organization-id" value={orgid ?? ""} />
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

              <Typography.Title level={4} className="general-settings-subtitle">
                Organizational default execution mode
              </Typography.Title>
              <Typography.Paragraph type="secondary">
                Suggested to new workspaces created in this organization. Existing workspaces keep their mode.
              </Typography.Paragraph>
              <Form.Item name="executionMode" label="Workspaces">
                <RadioChoices
                  options={[
                    {
                      value: "remote",
                      label: "Remote",
                      help: "Terrakube hosts your plans and applies, allowing you and your team to collaborate and review jobs in the app.",
                    },
                    {
                      value: "local",
                      label: "Local",
                      help: "Your planning and applying jobs are performed on your own machines. Terrakube is used just for storing and syncing the state.",
                    },
                  ]}
                />
              </Form.Item>
            </SettingsForm>

            <DangerZone
              actionName="Delete this organization"
              description={`Deleting the ${organization?.attributes?.name} organization will permanently delete all workspaces associated with it. Please be certain that you understand this. This action cannot be undone.`}
              disabled={!managePermission}
              confirmValue={organization?.attributes?.name ?? ""}
              confirmMessage="The organization will be permanently deleted and all its workspaces will be marked as deleted. This action cannot be undone."
              onConfirm={onDelete}
            />
          </Loading>
        </Loading>
      )}
    </div>
  );
};
