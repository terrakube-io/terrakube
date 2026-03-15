import { Button, Form, Input, Space, Spin, message, Typography } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
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

export const EditFederatedCredential = ({ mode, setMode, federatedId, loadFederated }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(true);
  const [form] = Form.useForm();

  useEffect(() => {
    if (mode === "edit" && federatedId) {
      setLoading(true);
      loadFederatedCredential(federatedId);
    } else {
      form.resetFields();
      setLoading(false);
    }
  }, [federatedId]);

  const loadFederatedCredential = (id: string) => {
    axiosInstance
      .get(`federated/${id}`)
      .then((response) => {
        const attrs = response.data.data.attributes;
        form.setFieldsValue({
          name: attrs.name,
          issuerUrl: attrs.issuerUrl,
          audience: attrs.audience,
        });
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const onFinish = (values: FederatedForm) => {
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

    if (mode === "create") {
      axiosInstance
        .post(`federated`, body, {
          headers: { "Content-Type": "application/vnd.api+json" },
        })
        .then(() => {
          message.success("Federated credential created successfully");
          setMode("list");
          loadFederated();
        })
        .catch((err) => {
          message.error(getErrorMessage(err));
        });
    } else {
      axiosInstance
        .patch(`federated/${federatedId}`, {
          data: {
            id: federatedId,
            ...body.data,
          },
          headers: { "Content-Type": "application/vnd.api+json" }
        })
        .then(() => {
          message.success("Federated credential updated successfully");
          setMode("list");
          loadFederated();
        })
        .catch((err) => {
          message.error(getErrorMessage(err));
        });
    }
  };

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
