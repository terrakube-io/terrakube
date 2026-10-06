import { ExportOutlined } from "@ant-design/icons";
import { Alert, Button, Flex, Spin, Typography } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { UserToken } from "@/modules/user/types";
import "./PatSection.css";
import CreatePatModal from "@/components/modals/CreatePatModal";
import { CreateTokenForm } from "@/modules/token/types";
import userService from "@/modules/user/userService";
import useApiRequest from "@/modules/api/useApiRequest";
import TokenGrid from "@/modules/token/TokenGrid";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";

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
      <SettingsPageHeader
        title="Tokens"
        divider={false}
        actions={
          <Button type="primary" onClick={() => setVisible(true)}>
            Create an API token
          </Button>
        }
      />
      <div className="pat-intro">
        <Typography.Paragraph>
          Your API tokens can be used to access the Terrakube API and perform all the actions your user account is
          entitled to. For more information, see the{" "}
          <Typography.Link
            href="https://docs.terrakube.io/user-guide/organizations/api-tokens"
            target="_blank"
            rel="noopener noreferrer"
          >
            user API tokens documentation <ExportOutlined />
          </Typography.Link>
          .
        </Typography.Paragraph>
        <Typography.Paragraph>
          Treat these tokens like passwords, as they can be used to access your account without a username, password, or
          two-factor authentication.
        </Typography.Paragraph>
      </div>

      {error && (
        <Alert className="alert" title="Failed to load tokens. Please try again later" type="error" showIcon banner />
      )}

      {loading && (
        <Flex align="center" className="loader" vertical gap="middle">
          <Spin size="large" />
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
          open={visible}
          onCancel={() => setVisible(false)}
          onCreated={() => loadTokens()}
          action={(values?: CreateTokenForm) => userService.createPersonalAccessToken(values!)}
        />
      )}
    </div>
  );
};
