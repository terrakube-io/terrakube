import "./ApprovalAttribution.css";

type Props = {
  approvedBy?: string | null;
  approvedAt?: string | null;
};

export const ApprovalAttribution = ({ approvedBy, approvedAt }: Props) =>
  approvedBy ? (
    <span className="approval-attribution">
      Approved by <b>{approvedBy}</b>
      {approvedAt && (
        <>
          {" "}
          on <time dateTime={approvedAt}>{new Date(approvedAt).toLocaleString()}</time>
        </>
      )}
    </span>
  ) : null;
