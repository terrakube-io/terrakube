import { Alert, Flex, Typography } from "antd";
import { UserToken } from "@/modules/user/types";
import TokenGridItem from "./TokenGridItem";
import "./TokenList.css";
import useApiRequest from "@/modules/api/useApiRequest";

type Props = {
  tokens: UserToken[];
  onDeleted: () => void;
  action: (id: string) => Promise<ApiResponse<undefined>>;
};

export default function TokenGrid({ tokens, action, onDeleted }: Props) {
  const { loading, execute, error } = useApiRequest({
    action: action,
    onReturn: () => {
      onDeleted();
    },
  });

  return (
    <div className="token-list">
      <Typography.Title level={4} className="list-header">
        Tokens ({tokens.length})
      </Typography.Title>
      {error && <Alert message="Failed to delete token" type="error" showIcon banner />}
      <Flex vertical gap="middle" style={{ marginTop: error !== undefined ? "10px" : undefined }}>
        {tokens.map((tkn) => (
          <TokenGridItem key={tkn.id} token={tkn} onDelete={(id: string) => execute(id)} loading={loading} />
        ))}
      </Flex>
    </div>
  );
}
