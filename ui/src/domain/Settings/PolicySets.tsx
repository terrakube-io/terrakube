import React, { useEffect, useState } from "react";
import {
  Avatar,
  Button,
  Card,
  List,
  Modal,
  Space,
  Tag,
  Typography,
  message,
  theme,
} from "antd";
import {
  BranchesOutlined,
  DeleteOutlined,
  EditOutlined,
  GlobalOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { Loading } from "@/components/feedback/Loading";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { CreateEditPolicySet } from "./CreateEditPolicySet";
import "./Settings.css";

const { Text, Paragraph } = Typography;

type Props = {
  editorMode?: "new" | "edit";
  editorId?: string;
  managePermission?: boolean;
};

export const PolicySetsSettings: React.FC<Props> = ({
  editorMode,
  editorId,
  managePermission = true,
}) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const { token } = theme.useToken();

  const [policySets, setPolicySets] = useState<any[]>([]);
  const [attachmentCounts, setAttachmentCounts] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);
  const [pendingDelete, setPendingDelete] = useState<any | null>(null);

  const loadPolicySets = async () => {
    setLoading(true);
    try {
      const res = await axiosInstance.get(`policy_set?include=attachments`);
      const items = res.data?.data || [];
      // Filter by organization if needed
      const orgItems = items.filter((item: any) => {
        const orgRel = item.relationships?.organization?.data;
        return !orgRel || orgRel.id === orgid;
      });
      setPolicySets(orgItems);

      // Map attachment counts
      const counts: Record<string, number> = {};
      orgItems.forEach((p: any) => {
        const atts = p.relationships?.attachments?.data;
        counts[p.id] = Array.isArray(atts) ? atts.length : 0;
      });
      setAttachmentCounts(counts);
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!editorMode) {
      void loadPolicySets();
    }
  }, [editorMode, orgid]);

  const handleDelete = async (id: string) => {
    try {
      await axiosInstance.delete(`policy_set/${id}`);
      message.success("Policy set deleted successfully");
      setPendingDelete(null);
      void loadPolicySets();
    } catch (err: any) {
      message.error(getErrorMessage(err));
    }
  };

  if (editorMode === "new") {
    return <CreateEditPolicySet mode="create" managePermission={managePermission} />;
  }

  if (editorMode === "edit") {
    return (
      <CreateEditPolicySet
        mode="edit"
        policySetId={editorId}
        managePermission={managePermission}
      />
    );
  }

  const renderEnforcementTag = (level: string) => {
    switch (level?.toUpperCase()) {
      case "HARD_MANDATORY":
        return <Tag color="error">Hard Mandatory</Tag>;
      case "SOFT_MANDATORY":
        return <Tag color="warning">Soft Mandatory</Tag>;
      case "ADVISORY":
        return <Tag color="processing">Advisory</Tag>;
      default:
        return <Tag color="default">{level}</Tag>;
    }
  };

  return (
    <div>
      <SettingsPageHeader
        title="Policy Sets"
        description="Enforce organizational guardrails and security standards across workspaces using Open Policy Agent (OPA) policies."
        action={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate(`/organizations/${orgid}/settings/policies/new`)}
            disabled={!managePermission}
            data-testid="add-policy-set-btn"
          >
            New Policy Set
          </Button>
        }
      />

      {loading ? (
        <Loading />
      ) : (
        <Card styles={{ body: { padding: 0 } }}>
          <List
            itemLayout="horizontal"
            dataSource={policySets}
            locale={{
              emptyText: (
                <div style={{ padding: "40px 0", textAlign: "center" }}>
                  <SafetyCertificateOutlined
                    style={{ fontSize: 48, color: token.colorTextTertiary, marginBottom: 16 }}
                  />
                  <Typography.Title level={4}>No Policy Sets Configured</Typography.Title>
                  <Paragraph type="secondary" style={{ maxWidth: 450, margin: "0 auto 16px" }}>
                    Create your first policy set to validate Terraform and OpenTofu plans automatically with Rego rules.
                  </Paragraph>
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={() => navigate(`/organizations/${orgid}/settings/policies/new`)}
                    disabled={!managePermission}
                  >
                    Create Policy Set
                  </Button>
                </div>
              ),
            }}
            renderItem={(item) => {
              const attrs = item.attributes;
              const attachmentsCount = attachmentCounts[item.id] ?? 0;

              return (
                <List.Item
                  key={item.id}
                  style={{ padding: "16px 24px" }}
                  actions={[
                    <Button
                      key="edit"
                      type="text"
                      icon={<EditOutlined />}
                      onClick={() =>
                        navigate(`/organizations/${orgid}/settings/policies/edit/${item.id}`)
                      }
                      disabled={!managePermission}
                    >
                      Edit
                    </Button>,
                    <Button
                      key="delete"
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => setPendingDelete(item)}
                      disabled={!managePermission}
                    >
                      Delete
                    </Button>,
                  ]}
                >
                  <List.Item.Meta
                    avatar={
                      <Avatar
                        style={{ backgroundColor: token.colorPrimaryBg, color: token.colorPrimary }}
                        icon={<SafetyCertificateOutlined />}
                        size={42}
                      />
                    }
                    title={
                      <Space wrap align="center">
                        <Text strong style={{ fontSize: 16 }}>
                          {attrs.name}
                        </Text>
                        {renderEnforcementTag(attrs.enforcementLevel)}
                        {attrs.shadowEnforcementLevel && (
                          <Tag color="default">
                            Shadow: {attrs.shadowEnforcementLevel}
                          </Tag>
                        )}
                        {attrs.global ? (
                          <Tag color="gold" icon={<GlobalOutlined />}>
                            Global
                          </Tag>
                        ) : (
                          <Tag color="cyan">
                            {attachmentsCount} {attachmentsCount === 1 ? "Attachment" : "Attachments"}
                          </Tag>
                        )}
                        {attrs.overrideTeam && (
                          <Tag color="geekblue" icon={<TeamOutlined />}>
                            Override Team: {attrs.overrideTeam}
                          </Tag>
                        )}
                      </Space>
                    }
                    description={
                      <div style={{ display: "flex", flexDirection: "column", gap: 4, width: "100%", marginTop: 4 }}>
                        {attrs.description && <Text type="secondary">{attrs.description}</Text>}
                        <Space size={16} wrap style={{ fontSize: 12, color: token.colorTextSecondary }}>
                          {attrs.repository && (
                            <span>
                              <b>Repo:</b> {attrs.repository}
                            </span>
                          )}
                          {attrs.branch && (
                            <span>
                              <BranchesOutlined /> {attrs.branch}
                            </span>
                          )}
                          {attrs.folder && (
                            <span>
                              <b>Path:</b> {attrs.folder}
                            </span>
                          )}
                        </Space>
                      </div>
                    }
                  />
                </List.Item>
              );
            }}
          />
        </Card>
      )}

      {pendingDelete && (
        <DeleteConfirmationModal
          open={true}
          title="Delete Policy Set"
          description={`Are you sure you want to delete policy set "${pendingDelete.attributes?.name}"? Workspaces relying on this policy set will no longer be evaluated against it.`}
          onConfirm={() => handleDelete(pendingDelete.id)}
          onCancel={() => setPendingDelete(null)}
        />
      )}
    </div>
  );
};
