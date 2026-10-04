import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input, Spin, Table, Tooltip, message, Typography } from "antd";
import { useEffect, useState } from "react";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { FederatedClaim } from "../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import "./components/ResourceCard.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";

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
  const [newClaim, setNewClaim] = useState({ claimKey: "", claimValue: "" });
  const [saving, setSaving] = useState(false);

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
        message.error(`Could not load the federated credential: ${getErrorMessage(err)}`);
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const onFinish = async (values: FederatedForm) => {
    if (claims.length === 0) {
      message.error("Add at least one claim condition");
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

    setSaving(true);
    try {
      let savedId = federatedId;

      if (mode === "create") {
        const res = await axiosInstance.post(`federated`, body, {
          headers: JSONAPI_HEADERS,
        });
        savedId = res.data.data.id;
        message.success("Federated credential added");
      } else {
        await axiosInstance.patch(
          `federated/${federatedId}`,
          { data: { id: federatedId, ...body.data } },
          { headers: JSONAPI_HEADERS }
        );
        message.success("Federated credential updated");
      }

      await saveClaims(savedId!);
      setMode("list");
      loadFederated();
    } catch (err: unknown) {
      message.error(`Could not save the federated credential: ${getErrorMessage(err)}`);
    } finally {
      setSaving(false);
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
    const claimKey = newClaim.claimKey.trim();
    const claimValue = newClaim.claimValue.trim();
    if (!claimKey || !claimValue) {
      message.warning("Enter both a claim key and a value");
      return;
    }
    setClaims([...claims, { key: `new-${Date.now()}`, claimKey, claimValue }]);
    setNewClaim({ claimKey: "", claimValue: "" });
  };

  const removeClaim = (key: string) => {
    setClaims(claims.filter((c) => c.key !== key));
  };

  // Enter adds the condition instead of submitting the whole form.
  const addOnEnter = (event: React.KeyboardEvent) => {
    event.preventDefault();
    addClaim();
  };

  const claimColumns = [
    {
      title: "Claim key",
      dataIndex: "claimKey",
      key: "claimKey",
      render: (value: string) => <span className="resource-mono">{value}</span>,
    },
    {
      title: "Value",
      dataIndex: "claimValue",
      key: "claimValue",
      render: (value: string) => <span className="resource-mono">{value}</span>,
    },
    {
      title: <span className="resource-sr-only">Actions</span>,
      key: "action",
      width: 56,
      render: (_: unknown, record: ClaimRow) => (
        <Tooltip title="Remove">
          <Button
            icon={<DeleteOutlined />}
            aria-label={`Remove claim condition ${record.claimKey}`}
            onClick={() => removeClaim(record.key)}
          />
        </Tooltip>
      ),
    },
  ];

  return (
    <Spin spinning={loading}>
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/workspaces/dynamic-provider-credentials"
        title={mode === "create" ? "Add a federated credential" : "Edit federated credential"}
        description="Trust tokens from an identity provider and give them a team's permissions."
        divider={false}
      />
      <SettingsForm
        form={form}
        onFinish={onFinish}
        saving={saving}
        saveLabel={mode === "create" ? "Add federated credential" : "Update federated credential"}
      >
        <Form.Item
          name="name"
          label="Team name"
          extra="Matching tokens get exactly this existing team's permissions."
          rules={[{ required: true, message: "Enter the name of an existing team" }]}
        >
          <Input placeholder="TERRAKUBE_AUTOMATION" />
        </Form.Item>
        <Form.Item name="issuerUrl" label="Issuer URL" rules={[{ required: true, message: "Issuer URL is required" }]}>
          <Input className="resource-mono" placeholder="https://token.actions.githubusercontent.com" />
        </Form.Item>
        <Form.Item
          name="audience"
          label="Audience"
          extra="Tokens must list this value in their aud claim."
          rules={[{ required: true, message: "Audience is required" }]}
        >
          <Input className="resource-mono" placeholder="terrakube-audience" />
        </Form.Item>

        <SettingsSection
          maxWidth="100%"
          title="Claim conditions"
          description={
            <>
              A token is accepted only when every condition matches, for example{" "}
              <Typography.Text code>repository_owner</Typography.Text>,{" "}
              <Typography.Text code>groups_direct</Typography.Text> or <Typography.Text code>amr</Typography.Text>.
            </>
          }
        >
          <Table
            columns={claimColumns}
            dataSource={claims}
            pagination={false}
            size="small"
            scroll={{ x: "max-content" }}
            locale={{ emptyText: "No conditions yet. Add at least one." }}
            className="federated-claims"
          />
          <Form.Item label="Claim key" htmlFor="claim-key">
            <Input
              id="claim-key"
              className="resource-mono"
              placeholder="repository_owner"
              value={newClaim.claimKey}
              onChange={(e) => setNewClaim({ ...newClaim, claimKey: e.target.value })}
              onPressEnter={addOnEnter}
            />
          </Form.Item>
          <Form.Item label="Value" htmlFor="claim-value">
            <Input
              id="claim-value"
              className="resource-mono"
              placeholder="terrakube-org"
              value={newClaim.claimValue}
              onChange={(e) => setNewClaim({ ...newClaim, claimValue: e.target.value })}
              onPressEnter={addOnEnter}
            />
          </Form.Item>
          <Button icon={<PlusOutlined />} onClick={addClaim}>
            Add condition
          </Button>
        </SettingsSection>
      </SettingsForm>
    </Spin>
  );
};
