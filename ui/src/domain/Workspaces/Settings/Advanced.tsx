import { DeleteOutlined } from "@ant-design/icons";
import { Button, Popconfirm, Space, Typography, message } from "antd";
import { useNavigate } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import { Workspace } from "../../types";
import { genericHeader } from "../Workspaces";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import { SettingsPageHeader } from "@/components/SettingsPageHeader";

const { Text } = Typography;

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
};

export const WorkspaceAdvanced = ({ workspace, manageWorkspace }: Props) => {
  const organizationId = workspace.relationships.organization.data.id;
  const navigate = useNavigate();
  const characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  function generateRandomString(length: number) {
    let result = "";
    const charactersLength = characters.length;
    for (let i = 0; i < length; i++) {
      result += characters.charAt(Math.floor(Math.random() * charactersLength));
    }

    return result;
  }
  const onDelete = (workspace: Workspace) => {
    const id = workspace.id;
    const randomLetters = generateRandomString(4);
    const deletedName = `${workspace.attributes.name.substring(0, 21)}_DEL_${randomLetters}`;

    const body = {
      data: {
        type: "workspace",
        id: id,
        attributes: {
          name: deletedName,
          deleted: "true",
        },
      },
    };
    axiosInstance
      .patch(
        `/organization/${organizationId}/workspace/${id}/relationships/vcs`,
        {
          data: null,
        },
        {
          headers: {
            "Content-Type": "application/vnd.api+json",
          },
        }
      )
      .then(() =>
        axiosInstance.patch(`organization/${organizationId}/workspace/${id}`, body, genericHeader).then((response) => {
          if (response.status === 204) {
            message.success("Workspace deleted successfully");
            navigate(`/organizations/${organizationId}/workspaces`);
          } else {
            message.error("Workspace deletion failed");
          }
        })
      )
      .catch((error) => {
        console.error("error deleting workspace:", error);
        message.error(getErrorMessage(error));
      });
  };

  const isLocked = workspace.attributes?.locked;
  const resourceCount = workspace.attributes?.resourceCount ?? 0;

  return (
    <div className="generalSettings">
      <SettingsPageHeader
        title="Destruction and Deletion"
        description="There are two independent steps for destroying this workspace and any infrastructure associated with it. First, any Terraform infrastructure managed by this workspace can be destroyed. Then, the workspace in Terrakube, including any variables, settings, and alert history can be deleted."
      />

      <SettingsSection
        danger
        title="Delete this Workspace"
        description={
          <>
            <Text strong>Warning!</Text> Deleting this workspace permanently removes all of its variables, settings,
            alert history, run history, and Terraform state.
            <br />
            This workspace is {isLocked ? "locked" : "unlocked"} and is
            {resourceCount > 0 ? ` managing ${resourceCount} resources` : " not managing any resources"}.
          </>
        }
      >
        <Popconfirm
          okButtonProps={{ danger: true }}
          onConfirm={() => {
            onDelete(workspace);
          }}
          title={
            <p>
              Workspace will be permanently deleted <br /> from this organization.
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
            disabled={!manageWorkspace}
          >
            <Space>
              <DeleteOutlined />
              Delete from Terrakube
            </Space>
          </Button>
        </Popconfirm>
      </SettingsSection>
    </div>
  );
};
