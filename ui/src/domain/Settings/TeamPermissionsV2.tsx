import React, { useEffect } from "react";
import { Col, Form, Radio, Row, Space, Switch, Table, Tag, Tooltip, Typography } from "antd";
import {
  InfoCircleOutlined,
  CrownOutlined,
  EditOutlined,
  EyeOutlined,
  CodeOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import "./Settings.css";

type TeamPermissionsV2Props = {
  managePermissions: boolean;
};

export type TeamRole = "admin" | "write" | "plan" | "read" | "custom";

type PermissionCategory = {
  category: string;
  permissions: {
    name: string;
    label: string;
    tooltip: string;
  }[];
};

// Role permission matrix matching RbacV2Service.java
const rolePermissionMatrix: Record<TeamRole, Record<string, boolean>> = {
  admin: {
    manageWorkspace: true,
    manageModule: true,
    manageProvider: true,
    manageVcs: true,
    manageTemplate: true,
    manageState: true,
    manageJob: true,
    manageCollection: true,
    planJob: true,
    approveJob: true,
  },
  write: {
    manageWorkspace: true,
    manageModule: false,
    manageProvider: false,
    manageVcs: false,
    manageTemplate: false,
    manageState: true,
    manageJob: true,
    manageCollection: false,
    planJob: true,
    approveJob: true,
  },
  plan: {
    manageWorkspace: false,
    manageModule: false,
    manageProvider: false,
    manageVcs: false,
    manageTemplate: false,
    manageState: false,
    manageJob: false,
    manageCollection: false,
    planJob: true,
    approveJob: false,
  },
  read: {
    manageWorkspace: false,
    manageModule: false,
    manageProvider: false,
    manageVcs: false,
    manageTemplate: false,
    manageState: false,
    manageJob: false,
    manageCollection: false,
    planJob: false,
    approveJob: false,
  },
  custom: {},
};

const roleDescriptions: Record<TeamRole, { label: string; color: string; icon: React.ReactNode; description: string }> =
  {
    admin: {
      label: "Admin",
      color: "red",
      icon: <CrownOutlined />,
      description:
        "Full control over all resources including workspace settings, team permissions, and infrastructure changes.",
    },
    write: {
      label: "Write",
      color: "orange",
      icon: <EditOutlined />,
      description: "Can plan and apply runs, manage workspaces, and read/write state and variables.",
    },
    plan: {
      label: "Plan",
      color: "blue",
      icon: <CodeOutlined />,
      description:
        "Can queue plans to propose infrastructure changes, but cannot apply them. Changes require approval from a Write or Admin user.",
    },
    read: {
      label: "Read",
      color: "default",
      icon: <EyeOutlined />,
      description: "Can view workspaces, runs, state, and variables. Cannot make any changes.",
    },
    custom: {
      label: "Custom",
      color: "purple",
      icon: <SettingOutlined />,
      description: "Fine-grained permission control. Select individual permissions below.",
    },
  };

const roleAccents: Record<TeamRole, string> = {
  admin: "#f5222d",
  write: "#fa8c16",
  plan: "#1677ff",
  read: "#8c8c8c",
  custom: "#722ed1",
};

const permissionCategories: PermissionCategory[] = [
  {
    category: "Run Access",
    permissions: [
      {
        name: "planJob",
        label: "Plan Runs",
        tooltip: "Queue Terraform/OpenTofu plans in workspaces",
      },
      {
        name: "approveJob",
        label: "Apply Runs",
        tooltip: "Approve and apply Terraform/OpenTofu plans, causing changes to real infrastructure",
      },
    ],
  },
  {
    category: "Resource Management",
    permissions: [
      {
        name: "manageWorkspace",
        label: "Manage Workspaces",
        tooltip: "Create, update, and delete workspaces and projects",
      },
      {
        name: "manageModule",
        label: "Manage Modules",
        tooltip: "Publish and delete modules in the private registry",
      },
      {
        name: "manageProvider",
        label: "Manage Providers",
        tooltip: "Publish and delete providers in the private registry",
      },
      {
        name: "manageTemplate",
        label: "Manage Templates",
        tooltip: "Create, update, and delete workflow templates",
      },
      {
        name: "manageCollection",
        label: "Manage Collections",
        tooltip: "Create, update, and delete variable collections",
      },
    ],
  },
  {
    category: "Infrastructure Settings",
    permissions: [
      {
        name: "manageState",
        label: "Manage State",
        tooltip: "Download, upload, and view Terraform/OpenTofu state files",
      },
      {
        name: "manageVcs",
        label: "Manage VCS & SSH",
        tooltip: "Create, update, and delete VCS connections and SSH keys",
      },
    ],
  },
];

export const TeamPermissionsV2: React.FC<TeamPermissionsV2Props> = ({ managePermissions }) => {
  const form = Form.useFormInstance();
  const role: TeamRole = Form.useWatch("role", form) || "custom";

  // When a preset role is selected, update individual permission fields
  useEffect(() => {
    if (role !== "custom") {
      const permissions = rolePermissionMatrix[role];
      form.setFieldsValue(permissions);
    }
  }, [role, form]);

  const renderCategory = (category: PermissionCategory) => (
    <div key={category.category}>
      <Typography.Title level={5} style={{ marginBottom: 8 }}>
        {category.category}
      </Typography.Title>
      <Table
        dataSource={category.permissions}
        columns={[
          {
            title: "Permission",
            key: "label",
            render: (_: any, record: any) => (
              <Space>
                <span>{record.label}</span>
                <Tooltip title={record.tooltip}>
                  <Typography.Text type="secondary" style={{ cursor: "help" }}>
                    <InfoCircleOutlined />
                  </Typography.Text>
                </Tooltip>
              </Space>
            ),
          },
          {
            title: "Access",
            key: "access",
            align: "right" as const,
            width: 120,
            render: (_: any, record: any) => {
              if (role !== "custom") {
                const granted = rolePermissionMatrix[role]?.[record.name] ?? false;
                return <Tag color={granted ? "green" : "default"}>{granted ? "Granted" : "Denied"}</Tag>;
              }
              return (
                <Form.Item name={record.name} valuePropName="checked" noStyle>
                  <Switch disabled={!managePermissions} />
                </Form.Item>
              );
            },
          },
        ]}
        rowKey="name"
        pagination={false}
        bordered
        size="small"
      />
    </div>
  );

  return (
    <>
      <SettingsSection
        maxWidth={960}
        title="Role"
        description="Choose a preset role or select Custom for fine-grained control."
      >
        <Form.Item name="role" initialValue="custom" style={{ marginBottom: 0 }}>
          <Radio.Group disabled={!managePermissions} style={{ width: "100%" }}>
            <Row gutter={[16, 16]}>
              {(Object.entries(roleDescriptions) as [TeamRole, (typeof roleDescriptions)["admin"]][]).map(
                ([key, desc]) => (
                  <Col key={key} xs={24} md={12}>
                    <Radio
                      value={key}
                      className="execution-mode-option role-option"
                      style={{ "--role-accent": roleAccents[key] } as React.CSSProperties}
                    >
                      <Space align="center">
                        <Tag color={desc.color} icon={desc.icon}>
                          {desc.label}
                        </Tag>
                        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                          {desc.description}
                        </Typography.Text>
                      </Space>
                    </Radio>
                  </Col>
                )
              )}
            </Row>
          </Radio.Group>
        </Form.Item>
      </SettingsSection>

      <SettingsSection
        maxWidth={960}
        title="Permissions"
        description={
          role !== "custom" ? (
            <>
              Determined by the <Tag color={roleDescriptions[role].color}>{roleDescriptions[role].label}</Tag> role.
            </>
          ) : (
            "Select individual permissions for this team. This provides the most granular control but requires careful configuration."
          )
        }
      >
        <Row gutter={[24, 24]}>
          <Col xs={24} md={12}>
            <Space orientation="vertical" size={24} style={{ width: "100%" }}>
              {renderCategory(permissionCategories[0])}
              {renderCategory(permissionCategories[2])}
            </Space>
          </Col>
          <Col xs={24} md={12}>
            {renderCategory(permissionCategories[1])}
          </Col>
        </Row>
      </SettingsSection>
    </>
  );
};
