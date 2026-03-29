import { Button, Checkbox, Divider, Form, Input, Typography, message, Table, Space, Select } from "antd";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import axiosInstance from "../../../config/axiosConfig";
import { Workspace } from "../../types";
import { atomicHeader } from "../Workspaces";

const { Text } = Typography;

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
};

type UpdateStateSharedForm = {
  globalRemoteState: boolean;
};

interface SharedWorkspace {
  id: string;
  name: string;
}

export const WorkspaceStateShared = ({ workspace, manageWorkspace }: Props) => {
  const [form] = Form.useForm();
  const globalRemoteState = Form.useWatch("globalRemoteState", form);
  const organizationId = workspace.relationships.organization.data.id;
  const id = workspace.id;
  const [waiting, setWaiting] = useState(false);
  const [sharedWorkspaces, setSharedWorkspaces] = useState<SharedWorkspace[]>([]);
  const [loadingTable, setLoadingTable] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [options, setOptions] = useState<SharedWorkspace[]>([]);

  useEffect(() => {
    const fetchWorkspaceNames = async () => {
      const ids = workspace.attributes.sharedIds?.split(",").filter((id) => id.trim() !== "") || [];
      if (ids.length === 0) {
        setSharedWorkspaces([]);
        return;
      }

      setLoadingTable(true);
      const fetchedWorkspaces: SharedWorkspace[] = [];
      for (const workspaceId of ids) {
        try {
          const response = await axiosInstance.get(`/organization/${organizationId}/workspace/${workspaceId.trim()}`);
          fetchedWorkspaces.push({
            id: workspaceId.trim(),
            name: response.data.data.attributes.name,
          });
        } catch (error) {
          fetchedWorkspaces.push({
            id: workspaceId.trim(),
            name: "Unknown Workspace",
          });
        }
      }
      setSharedWorkspaces(fetchedWorkspaces);
      setLoadingTable(false);
    };

    fetchWorkspaceNames();
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

  const handleSelectWorkspace = async (workspaceId: string) => {
    if (sharedWorkspaces.find((ws) => ws.id === workspaceId)) {
      message.warning("Workspace already added");
      return;
    }

    const selectedWs = options.find((ws) => ws.id === workspaceId);
    if (!selectedWs) return;

    setWaiting(true);
    try {
      const updatedSharedWorkspaces = [...sharedWorkspaces, selectedWs];
      await updateSharedIds(updatedSharedWorkspaces);
    } catch (error) {
      message.error("Failed to add workspace");
    } finally {
      setWaiting(false);
    }
  };

  const handleDeleteWorkspace = async (workspaceId: string) => {
    const updatedSharedWorkspaces = sharedWorkspaces.filter((ws) => ws.id !== workspaceId);
    setWaiting(true);
    try {
      await updateSharedIds(updatedSharedWorkspaces);
    } catch (error) {
      message.error("Failed to delete workspace");
    } finally {
      setWaiting(false);
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
      message.success("Shared workspaces updated successfully");
    } else {
      throw new Error("Update failed");
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
          message.success("Workspace updated successfully");
        } else {
          message.error("Workspace update failed");
        }
        setWaiting(false);
      })
      .catch((error) => {
        message.error("Workspace update failed");
        setWaiting(false);
      });
  };

  return (
    <div className="generalSettings">
      <h1>State Shared</h1>
      <Text type="secondary">
        Configure how the state is shared across workspaces.
      </Text>
      <Divider />
      <Form
        form={form}
        layout="vertical"
        name="state-shared"
        onFinish={onFinish}
        initialValues={{
          globalRemoteState: workspace.attributes.globalRemoteState,
        }}
        disabled={!manageWorkspace}
      >
        <Form.Item
          name="globalRemoteState"
          valuePropName="checked"
          label="Global Remote State"
        >
          <Checkbox>Allow all workspaces in the organization to access this workspace state</Checkbox>
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={waiting}>
            Update Workspace
          </Button>
        </Form.Item>
      </Form>
      {!globalRemoteState && (
        <>
          <Divider />
          <h3>Shared Workspace</h3>
          <div style={{ marginBottom: 16 }}>
            <Select
              showSearch
              placeholder="Search workspace by name"
              filterOption={false}
              onSearch={fetchWorkspaceOptions}
              onSelect={handleSelectWorkspace}
              value={null}
              loading={fetching}
              style={{ width: "100%" }}
              disabled={!manageWorkspace}
              notFoundContent={
                fetching ? <Select.Option disabled value="searching">Searching...</Select.Option> : null
              }
            >
              {options.map((option) => (
                <Select.Option key={option.id} value={option.id}>
                  {option.name}
                </Select.Option>
              ))}
            </Select>
          </div>
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
              },
              {
                title: "Action",
                key: "action",
                render: (_, record) => (
                  <Button
                    type="link"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() => handleDeleteWorkspace(record.id)}
                    disabled={!manageWorkspace}
                  />
                ),
              },
            ]}
          />
        </>
      )}
    </div>
  );
};
