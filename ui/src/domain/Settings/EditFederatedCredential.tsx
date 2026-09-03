import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Flex, Form, Input, Space, Spin, Table, message, Typography, Row, Col } from "antd";
import { useEffect, useState } from "react";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { FederatedClaim } from "../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";

type Props = {
  mode: "edit" | "create";
  setMode: React.Dispatch<React.SetStateAction<"list" | "edit" | "create">>;
  federatedId?: string;
  loadFederated: () => void;
};

type FederatedForm = {
  name: string;
  issuerUrl: string;
  audience: string;
};

type ClaimRow = {
  key: string;
  id?: string;
  claimKey: string;
  claimValue: string;
};

const JSONAPI_HEADERS = { "Content-Type": "application/vnd.api+json" };

export const EditFederatedCredential = ({ mode, setMode, federatedId, loadFederated }: Props) => {
  const [loading, setLoading] = useState(true);
  const [form] = Form.useForm();
  const [claims, setClaims] = useState<ClaimRow[]>([]);
  const [claimForm] = Form.useForm();

  useEffect(() => {
    if (mode === "edit" && federatedId) {
      setLoading(true);
      loadFederatedCredential(federatedId);
    } else {
      form.resetFields();
      setClaims([]);
      setLoading(false);
    }
  }, [federatedId]);

  const loadFederatedCredential = (id: string) => {
    Promise.all([axiosInstance.get(`federated/${id}`), axiosInstance.get(`federated/${id}/claims`)])
      .then(([credentialRes, claimsRes]) => {
        const attrs = credentialRes.data.data.attributes;
        form.setFieldsValue({
          name: attrs.name,
          issuerUrl: attrs.issuerUrl,
          audience: attrs.audience,
        });
        const loadedClaims: ClaimRow[] = (claimsRes.data.data || []).map((c: FederatedClaim) => ({
          key: c.id,
          id: c.id,
          claimKey: c.attributes.claimKey,
          claimValue: c.attributes.claimValue,
        }));
        setClaims(loadedClaims);
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const onFinish = async (values: FederatedForm) => {
    if (claims.length === 0) {
      message.error("At least one claim condition is required");
      return;
    }

    const body = {
      data: {
        type: "federated",
        attributes: {
          name: values.name,
          issuerUrl: values.issuerUrl,
          audience: values.audience,
        },
      },
    };

    try {
      let savedId = federatedId;

      if (mode === "create") {
        const res = await axiosInstance.post(`federated`, body, {
          headers: JSONAPI_HEADERS,
        });
        savedId = res.data.data.id;
        message.success("Federated credential created successfully");
      } else {
        await axiosInstance.patch(
          `federated/${federatedId}`,
          { data: { id: federatedId, ...body.data } },
          { headers: JSONAPI_HEADERS }
        );
        message.success("Federated credential updated successfully");
      }

      await saveClaims(savedId!);
      setMode("list");
      loadFederated();
    } catch (err: any) {
      message.error(getErrorMessage(err));
    }
  };

  const saveClaims = async (fedId: string) => {
    // Load existing claims from backend to diff against
    let existingClaims: FederatedClaim[] = [];
    try {
      const res = await axiosInstance.get(`federated/${fedId}/claims`);
      existingClaims = res.data.data || [];
    } catch {
      // If federated was just created, there are no claims yet
    }

    const existingIds = new Set(existingClaims.map((c) => c.id));
    const currentIds = new Set(claims.filter((c) => c.id).map((c) => c.id));

    // Delete removed claims
    const toDelete = existingClaims.filter((c) => !currentIds.has(c.id));
    await Promise.all(toDelete.map((c) => axiosInstance.delete(`federated/${fedId}/claims/${c.id}`)));

    // Create new claims (no id)
    const toCreate = claims.filter((c) => !c.id);
    await Promise.all(
      toCreate.map((c) =>
        axiosInstance.post(
          `federated/${fedId}/claims`,
          {
            data: {
              type: "federated_claim",
              attributes: {
                claimKey: c.claimKey,
                claimValue: c.claimValue,
              },
            },
          },
          { headers: JSONAPI_HEADERS }
        )
      )
    );

    // Update existing claims that changed
    const toUpdate = claims.filter((c) => c.id && existingIds.has(c.id));
    await Promise.all(
      toUpdate.map((c) => {
        const existing = existingClaims.find((e) => e.id === c.id);
        if (
          existing &&
          (existing.attributes.claimKey !== c.claimKey || existing.attributes.claimValue !== c.claimValue)
        ) {
          return axiosInstance.patch(
            `federated/${fedId}/claims/${c.id}`,
            {
              data: {
                type: "federated_claim",
                id: c.id,
                attributes: {
                  claimKey: c.claimKey,
                  claimValue: c.claimValue,
                },
              },
            },
            { headers: JSONAPI_HEADERS }
          );
        }
        return Promise.resolve();
      })
    );
  };

  const addClaim = () => {
    const values = claimForm.getFieldsValue();
    if (!values.claimKey || !values.claimValue) {
      message.warning("Both claim key and value are required");
      return;
    }
    setClaims([
      ...claims,
      {
        key: `new-${Date.now()}`,
        claimKey: values.claimKey,
        claimValue: values.claimValue,
      },
    ]);
    claimForm.resetFields();
  };

  const removeClaim = (key: string) => {
    setClaims(claims.filter((c) => c.key !== key));
  };

  const claimColumns = [
    {
      title: "Claim Key",
      dataIndex: "claimKey",
      key: "claimKey",
    },
    {
      title: "Claim Value",
      dataIndex: "claimValue",
      key: "claimValue",
    },
    {
      title: "Action",
      key: "action",
      width: 80,
      render: (_: any, record: ClaimRow) => (
        <Button type="link" danger icon={<DeleteOutlined />} onClick={() => removeClaim(record.key)} />
      ),
    },
  ];

  return (
    <Spin spinning={loading}>
      <div className="edit-team">
        <SettingsPageHeader
          docUrl="https://docs.terrakube.io/user-guide/workspaces/dynamic-provider-credentials"
          title={mode === "create" ? "Create Federated Credential" : "Edit Federated Credential"}
          description="Federated credentials let external identity providers exchange OIDC tokens for Terrakube access without storing secrets."
        />
        <SettingsSection maxWidth={960}>
          <Form form={form} layout="vertical" onFinish={onFinish}>
            <Row gutter={16}>
              <Col xs={24} md={9}>
                <Form.Item
                  name="name"
                  label="Terrakube team name"
                  extra="The external identity receives exactly the permissions assigned to this existing team."
                  rules={[{ required: true, message: "Please enter an existing Terrakube team name" }]}
                >
                  <Input placeholder="e.g. TERRAKUBE_AUTOMATION" />
                </Form.Item>
              </Col>
              <Col xs={24} md={9}>
                <Form.Item
                  name="issuerUrl"
                  label="Issuer URL"
                  rules={[{ required: true, message: "Please enter the issuer URL" }]}
                >
                  <Input placeholder="e.g. https://token.actions.githubusercontent.com" />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item
                  name="audience"
                  label="Audience"
                  extra="Configure one accepted audience per credential. Tokens with a multi-value aud claim are supported."
                  rules={[{ required: true, message: "Please enter the audience" }]}
                >
                  <Input placeholder="e.g. terrakube-audience" />
                </Form.Item>
              </Col>
            </Row>

            <Typography.Title level={5} style={{ marginTop: 24 }}>
              Claim Conditions
            </Typography.Title>
            <Typography.Text type="secondary">
              Add at least one condition to restrict which tokens are accepted. All conditions must match for a token to
              be authorized.
            </Typography.Text>
            <div
              style={{
                marginTop: 12,
                padding: "12px",
                backgroundColor: "#fafafa",
                borderRadius: "4px",
                border: "1px solid #f0f0f0",
              }}
            >
              <Typography.Text type="secondary" style={{ fontSize: "12px", display: "block", marginBottom: 8 }}>
                <strong>Examples by provider:</strong>
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: "12px", display: "block", marginBottom: 4 }}>
                • <Typography.Text code>repository_owner</Typography.Text> (GitHub Actions)
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: "12px", display: "block", marginBottom: 4 }}>
                • <Typography.Text code>groups_direct</Typography.Text> (GitLab CI)
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: "12px", display: "block" }}>
                • <Typography.Text code>amr</Typography.Text> (Azure AD)
              </Typography.Text>
            </div>

            <Form form={claimForm} layout="inline" style={{ marginTop: 16, marginBottom: 16 }}>
              <Form.Item name="claimKey" style={{ flex: 1 }}>
                <Input placeholder="Claim key (e.g. repository_owner, groups_direct)" />
              </Form.Item>
              <Form.Item name="claimValue" style={{ flex: 1 }}>
                <Input placeholder="Claim value (e.g. terrakube-org)" />
              </Form.Item>
              <Form.Item>
                <Button icon={<PlusOutlined />} onClick={addClaim}>
                  Add
                </Button>
              </Form.Item>
            </Form>

            <Table
              columns={claimColumns}
              dataSource={claims}
              pagination={false}
              size="small"
              locale={{ emptyText: "At least one claim condition is required" }}
              style={{ marginBottom: 24 }}
            />

            <Form.Item>
              <Flex justify="flex-end">
                <Space>
                  <Button onClick={() => setMode("list")}>Cancel</Button>
                  <Button type="primary" htmlType="submit">
                    {mode === "create" ? "Create" : "Update"}
                  </Button>
                </Space>
              </Flex>
            </Form.Item>
          </Form>
        </SettingsSection>
      </div>
    </Spin>
  );
};
