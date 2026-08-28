import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, List, message, Popconfirm, Typography } from "antd";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Template } from "../types";
import { AddTemplate } from "./AddTemplate";
import { EditTemplate } from "./EditTemplate";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/AccessDeniedAlert";
import LoadingFallback from "@/components/LoadingFallback";
import { SettingsPageHeader } from "@/components/SettingsPageHeader";

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
                <LoadingFallback />
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
                        <Popconfirm
                          okButtonProps={{ danger: true }}
                          onConfirm={() => {
                            onDelete(item.id);
                          }}
                          style={{ width: "20px" }}
                          title={
                            <p>
                              This will permanently delete this template. <br />
                              Are you sure?
                            </p>
                          }
                          okText="Yes"
                          cancelText="No"
                        >
                          {" "}
                          <Button icon={<DeleteOutlined />} type="link" danger disabled={!managePermission}>
                            Delete
                          </Button>
                        </Popconfirm>,
                      ]}
                    >
                      <List.Item.Meta title={item.attributes.name} description={item.attributes.description} />
                    </List.Item>
                  )}
                />
              )}
            </SettingsSection>
          </div>
        )
      )}
    </div>
  );
};
