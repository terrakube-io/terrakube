import React, { useEffect } from "react";
import { Checkbox, Form, Typography } from "antd";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { RadioChoices } from "@/components/settings/RadioChoices";
import "./TeamsTagsVariables.css";

type TeamPermissionsV2Props = {
  managePermissions: boolean;
};

export type TeamRole = "admin" | "write" | "plan" | "read" | "custom";

type PermissionCategory = {
  category: string;
  permissions: {
    name: string;
    label: string;
    help: string;
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

// Shared with the team list so a role reads the same everywhere.
// Tag colors meet 4.5:1 in both themes (antd orange does not); custom is neutral.
export const teamRoles: Record<TeamRole, { label: string; color: string; description: string }> = {
  admin: {
    label: "Admin",
    color: "red",
    description: "Full control over all resources, including workspace settings, team permissions and infrastructure.",
  },
  write: {
    label: "Write",
    color: "magenta",
    description: "Can plan and apply runs, manage workspaces, and read and write state and variables.",
  },
  plan: {
    label: "Plan",
    color: "blue",
    description: "Can queue plans but not apply them; a Write or Admin member has to approve.",
  },
  read: {
    label: "Read",
    color: "default",
    description: "Can view workspaces, runs, state and variables, but not change anything.",
  },
  custom: {
    label: "Custom",
    color: "default",
    description: "Only the permissions you choose below.",
  },
};

const permissionCategories: PermissionCategory[] = [
  {
    category: "Run access",
    permissions: [
      { name: "planJob", label: "Plan runs", help: "Queue plans in workspaces." },
      {
        name: "approveJob",
        label: "Apply runs",
        help: "Approve and apply plans, which changes real infrastructure.",
      },
    ],
  },
  {
    category: "Resource management",
    permissions: [
      {
        name: "manageWorkspace",
        label: "Manage workspaces",
        help: "Create, update and delete workspaces and projects.",
      },
      { name: "manageModule", label: "Manage modules", help: "Publish and delete modules in the private registry." },
      {
        name: "manageProvider",
        label: "Manage providers",
        help: "Publish and delete providers in the private registry.",
      },
      { name: "manageTemplate", label: "Manage templates", help: "Create, update and delete workflow templates." },
      {
        name: "manageCollection",
        label: "Manage collections",
        help: "Create, update and delete variable collections.",
      },
    ],
  },
  {
    category: "Infrastructure settings",
    permissions: [
      { name: "manageState", label: "Manage state", help: "Download, upload and view state files." },
      {
        name: "manageVcs",
        label: "Manage VCS and SSH keys",
        help: "Create, update and delete VCS connections and SSH keys.",
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

  return (
    <>
      <SettingsSection title="Role" description="A preset role sets every permission; Custom lets you pick them.">
        <Form.Item name="role" initialValue="custom">
          <RadioChoices
            disabled={!managePermissions}
            aria-label="Role"
            options={(Object.entries(teamRoles) as [TeamRole, (typeof teamRoles)["admin"]][]).map(([value, r]) => ({
              value,
              label: r.label,
              help: r.description,
            }))}
          />
        </Form.Item>
      </SettingsSection>

      <SettingsSection
        title="Permissions"
        description={
          role !== "custom"
            ? `Set by the ${teamRoles[role].label} role. Choose Custom to change them.`
            : "Choose what members of this team can do across the organization."
        }
      >
        {permissionCategories.map((category) => (
          <div key={category.category} className="team-permission-group" role="group" aria-label={category.category}>
            <Typography.Title level={5}>{category.category}</Typography.Title>
            {category.permissions.map((permission) =>
              // Preset roles show their permissions without registering the fields, so the
              // submitted payload stays role-only, as before.
              role === "custom" ? (
                <Form.Item key={permission.name} name={permission.name} valuePropName="checked" extra={permission.help}>
                  <Checkbox disabled={!managePermissions}>{permission.label}</Checkbox>
                </Form.Item>
              ) : (
                <Form.Item key={permission.name} extra={permission.help}>
                  <Checkbox disabled checked={rolePermissionMatrix[role][permission.name] ?? false}>
                    {permission.label}
                  </Checkbox>
                </Form.Item>
              )
            )}
          </div>
        ))}
      </SettingsSection>
    </>
  );
};
