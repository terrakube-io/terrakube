type Props = {
  action: string;
  permission?: string;
  docPath?: string;
};

const DOCS_BASE = "https://docs.terrakube.io";

export default function PermissionErrorMessage({
  action,
  permission,
  docPath = "/user-guide/organizations/team-management",
}: Props) {
  return (
    <span>
      You are not authorized to {action}. <br /> Please contact your administrator and request{" "}
      {permission ? (
        <>
          the <b>{permission}</b> permission
        </>
      ) : (
        "access"
      )}
      . <br /> For more information, visit the{" "}
      <a target="_blank" rel="noopener noreferrer" href={`${DOCS_BASE}${docPath}`}>
        Terrakube documentation
      </a>
      .
    </span>
  );
}
