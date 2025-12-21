import { Row, Col, Alert } from "antd";
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
      {error && <Alert message="Failed to delete token" type="error" showIcon banner />}
      <Row wrap={true} gutter={[16, 16]} style={{ marginTop: error !== undefined ? "10px" : undefined }}>
        {tokens.map((tkn) => (
          <Col xxl={8} xl={12} md={24} key={tkn.id}>
            <TokenGridItem token={tkn} onDelete={(id: string) => execute(id)} loading={loading} />
          </Col>
        ))}
      </Row>
    </div>
  );
}
