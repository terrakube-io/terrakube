import { ClockCircleOutlined, DeleteOutlined, ExclamationCircleOutlined, InfoCircleOutlined } from "@ant-design/icons";
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Popconfirm,
  Row,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  theme,
} from "antd";
import CreatePatModal from "@/modules/token/modals/CreatePatModal";
import { CreatedToken, CreateTokenForm } from "@/modules/user/types";
import { apiPost } from "@/modules/api/apiWrapper";
import FormItem from "antd/es/form/FormItem";
import { DateTime } from "luxon";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { axiosClientAuth } from "../../config/axiosConfig";
import { TeamToken } from "../types";
import "./Settings.css";
import { TeamPermissions } from "./TeamPermissions";
import "./TeamPermissions.css";
const { Paragraph } = Typography;

type Props = {
  mode: "edit" | "create";
  setMode: React.Dispatch<React.SetStateAction<"list" | "edit" | "create">>;
  teamId?: string;
  loadTeams: () => void;
};

type CreateTeamForm = {
  name: string;
} & UpdatTeamForm;

type UpdatTeamForm = {
  manageCollection: boolean;
  manageJob: boolean;
  manageModule: boolean;
  manageProvider: boolean;
  manageState: boolean;
  manageTemplate: boolean;
  manageVcs: boolean;
  manageWorkspace: boolean;
};

export const EditTeam = ({ mode, setMode, teamId, loadTeams }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(true);
  const [loadingTokens, setLoadingTokens] = useState(true);
  const [form] = Form.useForm();
  const [formToken] = Form.useForm<CreateTokenForm>();
  const [teamName, setTeamName] = useState<string>();
  const [tokens, setTokens] = useState<TeamToken[]>([]);
  const [visible, setVisible] = useState(false);
  const [visibleToken, setVisibleToken] = useState(false);
  const [creating, setCreating] = useState(false);
  const [token, setToken] = useState("");
  const [createTokenDisabled, setCreateTokenDisabled] = useState(true);
  const { token: themeToken } = theme.useToken();

  useEffect(() => {
    if (mode === "edit" && teamId) {
      setLoading(true);
      setLoadingTokens(true);
      loadTeam(teamId);
    } else {
      form.resetFields();
      setLoading(false);
    }
  }, [teamId]);

  const loadTeam = (id: string) => {
    axiosInstance.get(`organization/${orgid}/team/${id}`).then((response) => {
      const name = response.data.data.attributes.name;
      setTeamName(name);
      form.setFieldsValue({
        manageState: response.data.data.attributes.manageState,
        manageProvider: response.data.data.attributes.manageProvider,
        manageModule: response.data.data.attributes.manageModule,
        manageWorkspace: response.data.data.attributes.manageWorkspace,
        manageVcs: response.data.data.attributes.manageVcs,
        manageTemplate: response.data.data.attributes.manageTemplate,
        manageCollection: response.data.data.attributes.manageCollection,
        manageJob: response.data.data.attributes.manageJob,
      });
      setLoading(false);
      if (name) {
        loadTokens(name);
        loadUserTeams(name);
      }
    });
  };

  const onCreate = (values: CreateTeamForm) => {
    const body = {
      data: {
        type: "team",
        attributes: {
          name: values.name,
          manageState: values.manageState,
          manageWorkspace: values.manageWorkspace,
          manageModule: values.manageModule,
          manageProvider: values.manageProvider,
          manageVcs: values.manageVcs,
          manageTemplate: values.manageTemplate,
          manageCollection: values.manageCollection,
          manageJob: values.manageJob,
        },
      },
    };

    axiosInstance
      .post(`organization/${orgid}/team`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        loadTeams();
        loadTokens(teamName);
        setMode("list");
        form.resetFields();
      });
  };

  const onNewToken = () => {
    formToken.resetFields();
    setVisible(true);
  };

  const onUpdate = (values: UpdatTeamForm) => {
    const body = {
      data: {
        type: "team",
        id: teamId,
        attributes: {
          manageState: values.manageState,
          manageWorkspace: values.manageWorkspace,
          manageModule: values.manageModule,
          manageProvider: values.manageProvider,
          manageVcs: values.manageVcs,
          manageTemplate: values.manageTemplate,
          manageCollection: values.manageCollection,
          manageJob: values.manageJob,
        },
      },
    };
    axiosInstance
      .patch(`organization/${orgid}/team/${teamId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        loadTeams();
        setMode("list");
        form.resetFields();
      });
  };

  const onFinish = (values: CreateTeamForm | UpdatTeamForm) => {
    if (mode === "edit") {
      onUpdate(values);
    } else {
      onCreate(values as CreateTeamForm);
    }
  };

  const onDelete = (id: string) => {
    axiosClientAuth
      .delete(`${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/access-token/v1/teams/${id}`)
      .then((response) => {
        loadTokens(teamName);
      });
  };

  const onCancel = () => {
    setMode("list");
    form.resetFields();
  };

  const onCreateToken = async (values: CreateTokenForm) => {
    const token = {
      ...values,
      minutes: 0, //values.minutes,
      hours: 0, //values.hours,
      group: teamName,
    };

    return await apiPost("/access-token/v1/teams", token);
  };

  const loadTokens = (teamName: string) => {
    axiosClientAuth
      .get(`${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/access-token/v1/teams`)
      .then((response) => {
        if (teamName) {
          const filteredTokens = response.data.filter((token: any) => token.group === teamName);
          console.log("access-tokens filtered.....")
          console.log(filteredTokens)
          setTokens(filteredTokens);
        } else {
          setTokens([]);
        }
        setLoadingTokens(false);
      });
  };

  const loadUserTeams = (teamName: string) => {
    axiosClientAuth
      .get(`${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/access-token/v1/teams/current-teams`)
      .then((response) => {
        if (response.data?.groups.includes(teamName)) {
          setCreateTokenDisabled(false);
        }
      });
  };

  return (
    <div>
      <h1>{mode === "edit" ? <>Team: {teamName}</> : "New team"} </h1>
      <Space className="chooseType" direction="vertical">
        {loading ? (
          <p>Data loading...</p>
        ) : (
          <Form name="team" form={form} onFinish={onFinish} layout="vertical">
            {mode === "create" ? (
              <Form.Item
                name="name"
                tooltip={{
                  title: "Must be a valid AD Group name",
                  icon: <InfoCircleOutlined />,
                }}
                label="Name"
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
            ) : (
              ""
            )}

            <TeamPermissions managePermissions={true} />

            <Space direction="horizontal" style={{ marginTop: "20px" }}>
              {mode === "create" ? (
                <Button onClick={onCancel} type="default">
                  Cancel
                </Button>
              ) : (
                <></>
              )}
              <Button type="primary" htmlType="submit">
                {mode === "edit" ? "Update team organization access" : "Create team"}
              </Button>
            </Space>
          </Form>
        )}
      </Space>

      {mode === "edit" ? (
        <>
          <h2 style={{ marginTop: "30px" }}>Team API Tokens</h2>
          <div className="App-text">
            You can use team API tokens to perform API actions. The token's access level matches the team's access
            level. For example, if a team can execute jobs on workspaces, the token can also create jobs on workspaces
            through the API.
          </div>
          <Tooltip title="A team token can only be generated if you are member of this team.">
            <Button type="primary" disabled={createTokenDisabled} onClick={onNewToken} htmlType="button">
              Create a Team Token
            </Button>
          </Tooltip>
          <h4 style={{ marginTop: "30px" }}>Team API Tokens List</h4>
          {loadingTokens ? (
            <p>Data loading...</p>
          ) : (
            <List
              itemLayout="horizontal"
              dataSource={tokens}
              renderItem={(item) => (
                <List.Item>
                  <List.Item.Meta
                    description={
                      <Card style={{ width: "100%" }}>
                        <Row>
                          <Col span={23}>
                            <h3>{item.description}</h3>
                          </Col>
                          <Col span={1}>
                            <Popconfirm
                              onConfirm={() => {
                                onDelete(item.id);
                              }}
                              style={{ width: "20px" }}
                              title={
                                <p>
                                  This operation is irreversible.
                                  <br />
                                  Are you sure you want to proceed? <br />
                                </p>
                              }
                              okText="Yes"
                              cancelText="No"
                            >
                              <Button icon={<DeleteOutlined />}></Button>
                            </Popconfirm>
                          </Col>
                        </Row>
                        <Row>
                          <Col span={20}>
                            <Tag icon={<ExclamationCircleOutlined />} color="warning">
                              {" "}
                              <b>
                                Expires{": "}
                                {item.days > 0 || item.minutes > 0 || item.hours > 0
                                  ? DateTime.fromISO(item.createdDate)
                                      .plus({ days: item.days, minutes: item.minutes, hours: item.hours })
                                      .toLocaleString(DateTime.DATETIME_MED)
                                  : "Token without expiration date"}
                              </b>
                            </Tag>
                          </Col>
                        </Row>
                        <br />
                        <Row>
                          <Col span={20} style={{ color: themeToken.colorTextSecondary }}>
                            <ClockCircleOutlined /> Created <b>{DateTime.fromISO(item.createdDate).toRelative()}</b> by
                            user <b>{item.createdBy}</b>
                          </Col>
                        </Row>
                      </Card>
                    }
                  />
                </List.Item>
              )}
            />
          )}
          <CreatePatModal
            visible={visible}
            onCancel={() => setVisible(false)}
            onCreated={() => loadTokens(teamName)}
            action={onCreateToken}
          />
        </>
      ) : (
        ""
      )}

      {mode === "edit" ? (
        <Button style={{ marginTop: "60px" }} onClick={onCancel} type="default">
          Go back to Teams list
        </Button>
      ) : (
        <></>
      )}
    </div>
  );
};
