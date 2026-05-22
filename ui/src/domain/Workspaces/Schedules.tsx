import { ClockCircleOutlined, DeleteOutlined, EditOutlined, InfoCircleOutlined } from "@ant-design/icons";
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from "antd";
import cronstrue from "cronstrue";
import { useEffect, useMemo, useState } from "react";
import { Cron } from "react-js-cron";
import "react-js-cron/dist/styles.css";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import * as C2Q from "cron-to-quartz";
import { FlatSchedule, Template } from "../types";

type ScheduleForm = {
  templateId: string;
};

const validateMessages = {
  required: "${label} is required!",
  pattern: {
    mismatch: "${label} is not valid cron expression!",
  },
};

type Props = {
  schedules: FlatSchedule[];
  manageWorkspace: boolean;
};

export const Schedules = ({ schedules, manageWorkspace }: Props) => {
  const workspaceId = sessionStorage.getItem(WORKSPACE_ARCHIVE);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [form] = Form.useForm();
  const [visible, setVisible] = useState(false);
  const [loading, setLoading] = useState(false);
  const [mode, setMode] = useState("create");
  const [scheduleId, setScheduleId] = useState("");
  const [templates, setTemplates] = useState([]);
  const defaultValue = "* * * * *";
  const [value, setValue] = useState(defaultValue);

  const VARIABLES_COLUMS = useMemo(() => {
    return [
      {
        title: "Id",
        dataIndex: "id",
        width: "30%",
        key: "id",
        render: (_: string, record: FlatSchedule) => {
          return record.id;
        },
      },
      {
        title: "Job Type",
        dataIndex: "jobType",
        key: "jobType",
        width: "10%",
        render: (_: string, record: FlatSchedule) => {
          return record.name;
        },
      },
      {
        title: "Schedule",
        dataIndex: "cron",
        key: "cron",
        width: "30%",
        render: (_: string, record: FlatSchedule) => {
          return (
            <span>
              <Tag color="default">cron: {record.cron} </Tag>
              <Tag icon={<InfoCircleOutlined />} color="default">
                {cronstrue.toString(record.cron, {
                  dayOfWeekStartIndexZero: false,
                })}
              </Tag>
            </span>
          );
        },
      },
      {
        title: "Actions",
        key: "action",
        render: (_: string, record: FlatSchedule) => {
          return (
            <div>
              <Button type="link" icon={<EditOutlined />} onClick={() => onEdit(record)} disabled={!manageWorkspace}>
                Edit
              </Button>
              <Popconfirm
                onConfirm={() => {
                  deleteSchedule(record.id, organizationId!, workspaceId!);
                }}
                title={
                  <p>
                    This will permanently delete this schedule <br />
                    Are you sure?
                  </p>
                }
                okText="Yes"
                cancelText="No"
              >
                {" "}
                <Button danger type="link" icon={<DeleteOutlined />} disabled={!manageWorkspace}>
                  Delete
                </Button>
              </Popconfirm>
            </div>
          );
        },
      },
    ];
  }, [organizationId, workspaceId, manageWorkspace]);

  useEffect(() => {
    setLoading(true);
    loadTemplates();
  }, [organizationId]);

  const loadTemplates = () => {
    axiosInstance.get(`organization/${organizationId}/template`).then((response) => {
      const templatesList = response.data.data.filter(function (obj: Template) {
        //exclude CLI based templates
        return (
          obj.attributes.name !== "Terraform-Plan/Apply-Cli" && obj.attributes.name !== "Terraform-Plan/Destroy-Cli"
        );
      });
      setTemplates(templatesList);
      setLoading(false);
    });
  };

  const onCancel = () => {
    setVisible(false);
  };
  const onEdit = (schedule: FlatSchedule) => {
    setMode("edit");
    setScheduleId(schedule.id);
    form.setFieldsValue({
      templateId: schedule.templateReference,
      cron: schedule.cron,
    });
    setVisible(true);
  };

  const onCreate = (values: ScheduleForm) => {
    const body = {
      data: {
        type: "schedule",
        attributes: {
          templateReference: values.templateId,
          cron: C2Q.getQuartz(value)[0]?.join(" "),
        },
      },
    };

    axiosInstance
      .post(`organization/${organizationId}/workspace/${workspaceId}/schedule`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        setVisible(false);
        form.resetFields();
      });
  };

  const onUpdate = (values: ScheduleForm) => {
    const body = {
      data: {
        type: "schedule",
        id: scheduleId,
        attributes: {
          templateReference: values.templateId,
          cron: C2Q.getQuartz(value)[0]?.join(" "),
        },
      },
    };

    axiosInstance
      .patch(`organization/${organizationId}/workspace/${workspaceId}/schedule/${scheduleId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        setVisible(false);
        form.resetFields();
      });
  };

  return (
    <div>
      <h2>Schedules</h2>
      <div>
        <Typography.Text type="secondary" className="App-text">
          Schedules allows you to automatically trigger a Job in your workspace on a scheduled basis.
        </Typography.Text>
      </div>
      <Table dataSource={schedules} columns={VARIABLES_COLUMS} rowKey="key" />
      <Button
        type="primary"
        icon={<ClockCircleOutlined />}
        htmlType="button"
        onClick={() => {
          setMode("create");
          form.resetFields();
          setVisible(true);
        }}
        disabled={!manageWorkspace}
      >
        Add schedule
      </Button>

      <Modal
        width="600px"
        open={visible}
        title={mode === "edit" ? "Edit schedule" : "Create new schedule"}
        okText="Save schedule"
        cancelText="Cancel"
        onCancel={onCancel}
        onOk={() => {
          form
            .validateFields()
            .then((values) => {
              if (mode === "create") onCreate(values);
              else onUpdate(values);
            })
            .catch((info) => {
              console.log("Validate Failed:", info);
            });
        }}
      >
        <Space style={{ width: "100%" }} direction="vertical">
          <Form name="create-org" form={form} layout="vertical" validateMessages={validateMessages}>
            <Form.Item
              name="templateId"
              label="Job Type"
              rules={[{ required: true }]}
              tooltip={{
                title: "Job to trigger in a scheduled basis",
                icon: <InfoCircleOutlined />,
              }}
            >
              {loading || !templates ? (
                <p>Data loading...</p>
              ) : (
                <Select>
                  {templates.map((item: Template) => (
                    <Select.Option value={item.id} key={item.id}>
                      {item.attributes.name}
                    </Select.Option>
                  ))}
                </Select>
              )}
            </Form.Item>
          </Form>
          <span>
            {" "}
            <span style={{ color: "#ff4d4f" }}>*</span> Cron
          </span>
          <Input name="cron" value={value} />
          <Cron value={value} setValue={setValue} />
        </Space>
      </Modal>
    </div>
  );
};

const deleteSchedule = (scheduleId: string, organizationId: string, workspaceId: string) => {
  axiosInstance
    .delete(`organization/${organizationId}/workspace/${workspaceId}/schedule/${scheduleId}`, {
      headers: {
        "Content-Type": "application/vnd.api+json",
      },
    })
    .then((response) => {
      console.log(response);
    });
};
