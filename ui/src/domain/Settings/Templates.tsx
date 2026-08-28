import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, List, message, Typography } from "antd";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Template } from "../types";
import { AddTemplate } from "./AddTemplate";
import { EditTemplate } from "./EditTemplate";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { Loading } from "@/components/feedback/Loading";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";

type Props = {
  editorMode?: "new" | "edit";
  editorId?: string;
  managePermission?: boolean;
};

export const TemplatesSettings = ({ editorMode, editorId, managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [pendingDelete, setPendingDelete] = useState<Template | null>(null);
  const navigate = useNavigate();
  const mode = editorMode ?? "list";
  const templateID = editorId;
  const closeEditor = () => navigate(`/organizations/${orgid}/settings/templates`);

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/template/${id}`)
      .then(() => {
        loadTemplates();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  useEffect(() => {
    setLoading(true);
    loadTemplates();
  }, [orgid, templateID]);

  const loadTemplates = () => {
    axiosInstance
      .get(`organization/${orgid}/template`)
      .then((response) => {
        setTemplates(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error("Failed to load templates");
        }
        setLoading(false);
      });
  };

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : (
        (mode === "new" && <AddTemplate setMode={closeEditor} loadTemplates={loadTemplates} />) ||
        (mode === "edit" && (
          <EditTemplate setMode={closeEditor} templateId={templateID} loadTemplates={loadTemplates} />
        )) || (
          <div>
            <SettingsPageHeader
              docUrl="https://docs.terrakube.io/user-guide/organizations/templates"
              title="Templates"
              description="Templates define the job flows a workspace can run, such as plan, apply, or custom steps."
              actions={
                <Link to={`/organizations/${orgid}/settings/templates/new`}>
                  <Button type="primary" htmlType="button" icon={<PlusOutlined />} disabled={!managePermission}>
                    Add a Template
                  </Button>
                </Link>
              }
            />
            <SettingsSection maxWidth="100%">
              {loading ? (
                <Loading loading description="Loading templates..." />
              ) : (
                <List
                  className="vcsList"
                  itemLayout="horizontal"
                  dataSource={templates}
                  renderItem={(item) => (
                    <List.Item
                      actions={[
                        <Button icon={<EditOutlined />} type="link" disabled={!managePermission}>
                          <Link to={`/organizations/${orgid}/settings/templates/edit/${item.id}`}>Edit</Link>
                        </Button>,
                        <Button
                          icon={<DeleteOutlined />}
                          type="link"
                          danger
                          disabled={!managePermission}
                          onClick={() => setPendingDelete(item)}
                        >
                          Delete
                        </Button>,
                      ]}
                    >
                      <List.Item.Meta title={item.attributes.name} description={item.attributes.description} />
                    </List.Item>
                  )}
                />
              )}
            </SettingsSection>
            <DeleteConfirmationModal
              open={pendingDelete !== null}
              title="Delete template"
              message={
                <>
                  Deleting the template <strong>{pendingDelete?.attributes.name}</strong> cannot be undone.
                </>
              }
              okText="Delete"
              onConfirm={() => {
                if (pendingDelete) onDelete(pendingDelete.id);
                setPendingDelete(null);
              }}
              onCancel={() => setPendingDelete(null)}
            />
          </div>
        )
      )}
    </div>
  );
};
