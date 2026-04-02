import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input, Space, Spin, Table, message, Typography } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { FederatedClaim } from "../types";
import "./Settings.css";

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
  const { orgid } = useParams();
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
    Promise.all([
      axiosInstance.get(`federated/${id}`),
      axiosInstance.get(`federated/${id}/claims`),
    ])
      .then(([credentialRes, claimsRes]) => {
        const attrs = credentialRes.data.data.attributes;
        form.setFieldsValue({
          name: attrs.name,
          issuerUrl: attrs.issuerUrl,
          audience: attrs.audience,
        });
        const loadedClaims: ClaimRow[] = (claimsRes.data.data || []).map(
          (c: FederatedClaim) => ({
            key: c.id,
            id: c.id,
            claimKey: c.attributes.claimKey,
            claimValue: c.attributes.claimValue,
          })
        );
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
    await Promise.all(
      toDelete.map((c) =>
        axiosInstance.delete(`federated/${fedId}/claims/${c.id}`)
      )
    );

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
          (existing.attributes.claimKey !== c.claimKey ||
            existing.attributes.claimValue !== c.claimValue)
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
        <Button
          type="link"
          danger
          icon={<DeleteOutlined />}
          onClick={() => removeClaim(record.key)}
        />
      ),
    },
  ];

  return (
    <Spin spinning={loading}>
      <div className="edit-team">
        <Typography.Title level={3}>
          {mode === "create" ? "Create Federated Credential" : "Edit Federated Credential"}
        </Typography.Title>
        <Form form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="name"
            label="Name"
            rules={[{ required: true, message: "Please enter the federated credential name" }]}
          >
            <Input placeholder="e.g. GitHub Actions" />
          </Form.Item>
          <Form.Item
            name="issuerUrl"
            label="Issuer URL"
            rules={[{ required: true, message: "Please enter the issuer URL" }]}
          >
            <Input placeholder="e.g. https://token.actions.githubusercontent.com" />
          </Form.Item>
          <Form.Item
            name="audience"
            label="Audience"
            rules={[{ required: true, message: "Please enter the audience" }]}
          >
            <Input placeholder="e.g. terrakube-audience" />
          </Form.Item>

          <Typography.Title level={5} style={{ marginTop: 24 }}>
            Claim Conditions
          </Typography.Title>
          <Typography.Text type="secondary">
            Add conditions to restrict which tokens are accepted. All conditions must match for a token to be authorized.
          </Typography.Text>
          <div style={{ marginTop: 12, padding: '12px', backgroundColor: '#fafafa', borderRadius: '4px', border: '1px solid #f0f0f0' }}>
            <Typography.Text type="secondary" style={{ fontSize: '12px', display: 'block', marginBottom: 8 }}>
              <strong>Examples by provider:</strong>
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: '12px', display: 'block', marginBottom: 4 }}>
              • <Typography.Text code>repository_owner</Typography.Text> (GitHub Actions)
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: '12px', display: 'block', marginBottom: 4 }}>
              • <Typography.Text code>groups_direct</Typography.Text> (GitLab CI)
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: '12px', display: 'block' }}>
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
            locale={{ emptyText: "No claim conditions - all tokens from this issuer will be accepted" }}
            style={{ marginBottom: 24 }}
          />

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                {mode === "create" ? "Create" : "Update"}
              </Button>
              <Button onClick={() => setMode("list")}>Cancel</Button>
            </Space>
          </Form.Item>
        </Form>
      </div>
    </Spin>
  );
};
