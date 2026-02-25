import { Alert, Button, Flex, Spin, Typography, Breadcrumb } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { UserToken } from "@/modules/user/types";
import "./PatSection.css";
import CreatePatModal from "@/modules/token/modals/CreatePatModal";
import { CreateTokenForm } from "@/modules/token/types";
import userService from "@/modules/user/userService";
import useApiRequest from "@/modules/api/useApiRequest";
import TokenGrid from "@/modules/token/TokenGrid";

type Params = {
  orgid: string;
};

export const Tokens = () => {
  const { orgid } = useParams<Params>();
  const [tokens, setTokens] = useState<UserToken[]>([]);
  const [visible, setVisible] = useState(false);
  const {
    loading,
    execute: loadTokens,
    error,
  } = useApiRequest({
    action: () => userService.listPersonalAccessTokens(),
    onReturn: (data) => {
      setTokens(data);
    },
  });

  useEffect(() => {
    loadTokens();
  }, [orgid]);

  return (
    <div className="pat-section">
      <Breadcrumb
        className="breadcrumb"
        items={[
          {
            title: "Settings",
          },
          {
            title: "Tokens",
          },
        ]}
      />
      <Flex gap="middle" justify="space-between" align="center" className="header-section">
        <Flex vertical>
          <Typography.Title level={2} className="title">
            Tokens
          </Typography.Title>
          <Typography.Text type="secondary" className="description">
            Your API tokens can be used to access the Terrakube API and perform all the actions your user account is
            entitled to. For more information, see the{" "}
            <a href="https://docs.terrakube.io/" target="_blank" rel="noreferrer">
              user API tokens documentation
            </a>
            .
          </Typography.Text>
          <Typography.Text type="secondary" className="warning-text">
            Treat these tokens like passwords, as they can be used to access your account without a username, password,
            or two-factor authentication.
          </Typography.Text>
        </Flex>
        <Button type="primary" onClick={() => setVisible(true)}>
          Create an API token
        </Button>
      </Flex>

      {error && (
        <Alert className="alert" message="Failed to load tokens. Please try again later" type="error" showIcon banner />
      )}

      {loading && (
        <Flex align="center" className="loader" vertical gap="middle">
          <Spin tip="Loading" size="large" />
          <Typography.Text>Loading tokens...</Typography.Text>
        </Flex>
      )}

      {!loading && (
        <TokenGrid
          tokens={tokens}
          action={(id) => userService.deletePersonalAccessToken(id!)}
          onDeleted={() => loadTokens()}
        />
      )}

      {visible && (
        <CreatePatModal
          visible={visible}
          onCancel={() => setVisible(false)}
          onCreated={() => loadTokens()}
          action={(values?: CreateTokenForm) => userService.createPersonalAccessToken(values!)}
        />
      )}
    </div>
  );
};
