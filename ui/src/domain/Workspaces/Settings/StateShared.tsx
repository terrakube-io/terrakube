import { Button, Checkbox, Form, Select, Spin, Table, Typography, message } from "antd";
import { DeleteOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import { Workspace } from "../../types";
import { atomicHeader } from "../Workspaces";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import "../Workspaces.css";

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
  onWorkspaceUpdate?: () => void;
};

type UpdateStateSharedForm = {
  globalRemoteState: boolean;
};

interface SharedWorkspace {
  id: string;
  name: string;
}

export const WorkspaceStateShared = ({ workspace, manageWorkspace, onWorkspaceUpdate }: Props) => {
  const [form] = Form.useForm();
  const globalRemoteState = Form.useWatch("globalRemoteState", form);
  const organizationId = workspace.relationships.organization.data.id;
  const id = workspace.id;
  const [waiting, setWaiting] = useState(false);
  const [sharedWorkspaces, setSharedWorkspaces] = useState<SharedWorkspace[]>([]);
  const [loadingTable, setLoadingTable] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [options, setOptions] = useState<SharedWorkspace[]>([]);
  const [pendingRemoval, setPendingRemoval] = useState<SharedWorkspace | null>(null);

  useEffect(() => {
    const ids =
      workspace.attributes.sharedIds
        ?.split(",")
        .map((id) => id.trim())
        .filter((id) => id !== "") || [];
    if (ids.length === 0) {
      setSharedWorkspaces([]);
      return;
    }

    setLoadingTable(true);
    Promise.all(
      ids.map((workspaceId) =>
        axiosInstance
          .get(`/organization/${organizationId}/workspace/${workspaceId}`)
          .then((response) => ({ id: workspaceId, name: response.data.data.attributes.name as string }))
          .catch(() => ({ id: workspaceId, name: "Unknown workspace" }))
      )
    ).then((fetched) => {
      setSharedWorkspaces(fetched);
      setLoadingTable(false);
    });
  }, [organizationId, workspace.attributes.sharedIds]);

  const fetchWorkspaceOptions = async (search: string) => {
    if (!search) {
      setOptions([]);
      return;
    }
    setFetching(true);
    try {
      const response = await axiosInstance.get(
        `/organization/${organizationId}/workspace?filter[workspace]=name==*${search}*`
      );
      const workspaces = response.data.data.map((item: any) => ({
        id: item.id,
        name: item.attributes.name,
      }));
      setOptions(workspaces);
    } catch (error) {
      console.error("Error fetching workspaces:", error);
    } finally {
      setFetching(false);
    }
  };

  const updateSharedIds = async (updatedWorkspaces: SharedWorkspace[]) => {
    const sharedIdsString = updatedWorkspaces.map((ws) => ws.id).join(",");
    const body = {
      "atomic:operations": [
        {
          op: "update",
          href: `/organization/${organizationId}/workspace/${id}`,
          data: {
            type: "workspace",
            id: id,
            attributes: {
              sharedIds: sharedIdsString,
            },
          },
        },
      ],
    };

    const response = await axiosInstance.post("/operations", body, atomicHeader);
    if (response.status === 200) {
      setSharedWorkspaces(updatedWorkspaces);
      message.success("Shared workspaces updated");
      onWorkspaceUpdate?.();
    } else {
      throw new Error(`HTTP ${response.status}`);
    }
  };

  const handleSelectWorkspace = async (workspaceId: string) => {
    if (sharedWorkspaces.find((ws) => ws.id === workspaceId)) {
      message.warning("This workspace already has access");
      return;
    }

    const selectedWs = options.find((ws) => ws.id === workspaceId);
    if (!selectedWs) return;

    setWaiting(true);
    try {
      await updateSharedIds([...sharedWorkspaces, selectedWs]);
    } catch (error) {
      message.error(`Could not give ${selectedWs.name} access: ${getErrorMessage(error)}`);
    } finally {
      setWaiting(false);
    }
  };

  const handleRemoveWorkspace = async (removed: SharedWorkspace) => {
    setPendingRemoval(null);
    setWaiting(true);
    try {
      await updateSharedIds(sharedWorkspaces.filter((ws) => ws.id !== removed.id));
    } catch (error) {
      message.error(`Could not remove access for ${removed.name}: ${getErrorMessage(error)}`);
    } finally {
      setWaiting(false);
    }
  };

  const onFinish = (values: UpdateStateSharedForm) => {
    setWaiting(true);
    const body = {
      "atomic:operations": [
        {
          op: "update",
          href: `/organization/${organizationId}/workspace/${id}`,
          data: {
            type: "workspace",
            id: id,
            attributes: {
              globalRemoteState: values.globalRemoteState,
            },
          },
        },
      ],
    };

    axiosInstance
      .post("/operations", body, atomicHeader)
      .then((response) => {
        if (response.status === 200) {
          message.success("State sharing updated");
          onWorkspaceUpdate?.();
        } else {
          message.error(`Could not update state sharing (HTTP ${response.status})`);
        }
      })
      .catch((error) => message.error(`Could not update state sharing: ${getErrorMessage(error)}`))
      .finally(() => setWaiting(false));
  };

  return (
    <div className="generalSettings">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/workspaces/share-workspace-state"
        title="State shared"
        description="Choose which other workspaces can read this workspace's state."
        divider={false}
      />
      <Spin spinning={waiting}>
        <SettingsForm
          form={form}
          name="state-shared"
          onFinish={onFinish}
          initialValues={{ globalRemoteState: workspace.attributes.globalRemoteState }}
          disabled={!manageWorkspace}
          saveDisabled={!manageWorkspace}
        >
          <Form.Item
            name="globalRemoteState"
            valuePropName="checked"
            label="Organization access"
            extra="State can contain secrets, and every workspace in the organization will be able to read it."
          >
            <Checkbox>{"Share this workspace's state with all workspaces in the organization"}</Checkbox>
          </Form.Item>
        </SettingsForm>

        {!globalRemoteState && (
          <div className="state-shared-access">
            <SettingsSection
              maxWidth={680}
              title="Workspaces with access"
              description="These workspaces can read this workspace's state. Adding or removing one applies immediately."
            >
              <Select<string>
                showSearch
                aria-label="Add a workspace by name"
                placeholder="Add a workspace by name"
                filterOption={false}
                onSearch={fetchWorkspaceOptions}
                onSelect={handleSelectWorkspace}
                value={null}
                loading={fetching}
                className="state-shared-picker"
                disabled={!manageWorkspace}
                notFoundContent={fetching ? "Searching..." : null}
                options={options
                  .filter((option) => option.id !== id)
                  .map((option) => ({ value: option.id, label: option.name }))}
              />
              <Table
                dataSource={sharedWorkspaces}
                loading={loadingTable}
                rowKey="id"
                columns={[
                  {
                    title: "Name",
                    dataIndex: "name",
                    key: "name",
                  },
                  {
                    title: "ID",
                    dataIndex: "id",
                    key: "id",
                    render: (value: string) => <Typography.Text className="state-shared-id">{value}</Typography.Text>,
                  },
                  {
                    title: "Action",
                    key: "action",
                    render: (_, record) => (
                      <Button
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        aria-label={`Remove access for ${record.name}`}
                        onClick={() => setPendingRemoval(record)}
                        disabled={!manageWorkspace}
                      />
                    ),
                  },
                ]}
              />
            </SettingsSection>
          </div>
        )}
      </Spin>
      <DeleteConfirmationModal
        open={pendingRemoval !== null}
        title={`Remove access for ${pendingRemoval?.name}?`}
        message={`${pendingRemoval?.name} will no longer be able to read this workspace's state.`}
        okText="Remove access"
        onConfirm={() => pendingRemoval && handleRemoveWorkspace(pendingRemoval)}
        onCancel={() => setPendingRemoval(null)}
      />
    </div>
  );
};
