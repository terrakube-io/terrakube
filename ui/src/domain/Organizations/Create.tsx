import { Button, Form, Input, message, Space, ColorPicker } from "antd";
import { useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { IconSelector } from "./IconSelector";
import { organizationNameRules } from "../../config/validation";
import { useState } from "react";
import PageWrapper from "@/components/PageWrapper/PageWrapper";
import "./Organizations.css";

const validateMessages = {
  required: "${label} is required!",
};

const DEFAULT_ICON = "FaBuilding";
const DEFAULT_COLOR = "#000000";

type CreateOrganizationForm = {
  name: string;
  description?: string;
  icon?: string;
};

type Props = {
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

export const CreateOrganization = ({ setOrganizationName }: Props) => {
  const navigate = useNavigate();

  const [icon, setIcon] = useState<string>(DEFAULT_ICON);
  const [color, setColor] = useState<string>(DEFAULT_COLOR);
  const [submitting, setSubmitting] = useState(false);

  const onFinish = (values: CreateOrganizationForm) => {
    // Guard against double-submits (e.g. an impatient second click before the
    // first request resolves) — without this, a slow request looks like nothing
    // happened, inviting a resubmit that then fails with a duplicate-name error
    // even though the first request already succeeded.
    if (submitting) return;
    setSubmitting(true);

    // Store as iconName:color (color always hex)
    const iconField = icon ? `${icon}:${color}` : undefined;
    const body = {
      data: {
        type: "organization",
        attributes: { ...values, icon: iconField },
      },
    };

    axiosInstance
      .post("organization", body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        // axios only resolves .then() for 2xx responses, so getting here always means success —
        // checking for one specific status code (e.g. 201) risks silently doing nothing if the
        // API ever returns a different 2xx.
        message.success("Organization created successfully");
        sessionStorage.setItem(ORGANIZATION_ARCHIVE, response.data.data.id);
        sessionStorage.setItem(ORGANIZATION_NAME, response.data.data.attributes.name);
        setOrganizationName(response.data.data.attributes.name);
        navigate(`/organizations/${response.data.data.id}/settings/teams`);
      })
      .catch((error) => {
        console.error("Failed to create organization:", error.response?.status, error.response?.data, error);
        if (error.response?.status === 403) {
          message.error(
            <span>
              You are not authorized to create Organizations. <br /> Please contact your administrator and request to
              include you in the Terrakube Administrator group. <br /> For more information, visit the{" "}
              <a
                target="_blank"
                rel="noopener noreferrer"
                href="https://docs.terrakube.io/getting-started/security#administrator-group"
              >
                Terrakube documentation
              </a>
              .
            </span>
          );
        } else {
          message.error(error.response?.data?.errors?.[0]?.detail || "Failed to create organization");
        }
      })
      .finally(() => {
        setSubmitting(false);
      });
  };

  return (
    <PageWrapper
      title="New Organization"
      subTitle="Organizations are privately shared spaces for teams to collaborate on infrastructure."
      breadcrumbs={[{ label: "Organizations", path: "/" }, { label: "New" }]}
      width="form"
    >
      <Form layout="vertical" name="create-org" onFinish={onFinish} validateMessages={validateMessages}>
        <Form.Item
          name="name"
          label="Organization name"
          tooltip="e.g. company-name"
          extra=" Organization names must be unique and will be part of your resource names used in various tools, for example development, production, finance."
          rules={organizationNameRules}
        >
          <Input />
        </Form.Item>

        <Form.Item name="description" label="Description">
          <Input.TextArea />
        </Form.Item>

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

        <Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting} disabled={submitting}>
            Create organization
          </Button>
        </Form.Item>
      </Form>
    </PageWrapper>
  );
};
