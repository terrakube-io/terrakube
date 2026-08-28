import { Alert, Flex, Typography } from "antd";
import { useState } from "react";
import { UserToken } from "@/modules/user/types";
import TokenGridItem from "./TokenGridItem";
import "./TokenList.css";
import useApiRequest from "@/modules/api/useApiRequest";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";

type Props = {
  tokens: UserToken[];
  onDeleted: () => void;
  action: (id: string) => Promise<ApiResponse<undefined>>;
};

export default function TokenGrid({ tokens, action, onDeleted }: Props) {
  const [pendingDelete, setPendingDelete] = useState<UserToken | null>(null);
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
      {error && <Alert title="Failed to delete token" type="error" showIcon banner />}
      <Flex vertical gap="middle" style={{ marginTop: error !== undefined ? "10px" : undefined }}>
        {tokens.map((tkn) => (
          <TokenGridItem key={tkn.id} token={tkn} onDelete={() => setPendingDelete(tkn)} loading={loading} />
        ))}
      </Flex>
      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete token"
        message={`This will permanently delete the token "${pendingDelete?.description}". This operation is irreversible.`}
        okText="Delete"
        onConfirm={() => {
          if (pendingDelete) {
            execute(pendingDelete.id);
          }
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
}
