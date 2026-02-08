import {
  CheckCircleOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CommentOutlined,
  ExclamationCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  StopOutlined,
  SyncOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Avatar, Button, Card, Collapse, message, Radio, RadioChangeEvent, Space, Spin, Tag } from "antd";
import { AxiosResponse } from "axios";
import parse from "html-react-parser";
import { DateTime } from "luxon";
import { useEffect, useState } from "react";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { axiosClient } from "../../config/axiosConfig";
import { Job, JobStep } from "../types";
import { TerminalOutput } from "./TerminalOutput";

type Props = {
  jobId: string;
};

export const DetailsJob = ({ jobId }: Props) => {
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [loading, setLoading] = useState(false);
  const [job, setJob] = useState<AxiosResponse<Job>>();
  const [workspaceSource, setWorkspaceSource] = useState<String>();
  const [workspaceDefaultBranch, setWorkspaceDefaultBranch] = useState<String>();
  const [workspaceVcsId, setWorkspaceVcsId] = useState<String>();
  const [workspaceVcsName, setWorkspaceVcsName] = useState<String>();
  const [steps, setSteps] = useState<JobStep[]>([]);
  const [uiType, setUIType] = useState("structured");
  const [uiTemplates, setUITemplates] = useState<Record<number, string>>({});
  const outputLog = async (output: string, status: string) => {
    if (output != null) {
      const apiDomain = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).hostname;
      if (output.includes(apiDomain))
        return axiosInstance
          .get(output)
          .then((resp) => resp.data)
          .catch(() => "No logs available");
      else
        return axiosClient
          .get(output)
          .then((resp) => resp.data)
          .catch(() => "No logs available");
    } else {
      if (status === "running") return "Initializing the backend...";
      else return "Waiting logs...";
    }
  };

  const handleComingSoon = () => {
    message.info("Coming Soon!");
  };

  const onChange = (e: RadioChangeEvent) => {
    setUIType(e.target.value);
  };

  const handleCancel = () => {
    const body = {
      data: {
        type: "job",
        id: jobId,
        attributes: {
          status: "cancelled",
        },
      },
    };

    axiosInstance
      .patch(`organization/${organizationId}/job/${jobId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Job Cancelled Succesfully");
        loadJob();
      })
      .catch((error) => {
        message.error("Could not cancel job: " + error.response.data.errors[0].detail);
      });
  };

  const getIconStatus = (item: JobStep) => {
    switch (item.status) {
      case "completed":
        return <CheckCircleOutlined style={{ fontSize: "20px", color: "#52c41a" }} />;
      case "noChanges":
        return <CheckCircleOutlined style={{ fontSize: "20px", color: "#52c41a" }} />;
      case "notExecuted":
        return <CheckCircleOutlined style={{ fontSize: "20px", color: "#fa8f37" }} />;
      case "running":
        return <SyncOutlined spin style={{ color: "#108ee9", fontSize: "20px" }} />;
      case "failed":
        return <CloseCircleOutlined style={{ fontSize: "20px", color: "#FB0136" }} />;
      case "cancelled":
        return <CloseCircleOutlined style={{ fontSize: "20px", color: "#FB0136" }} />;
      default:
        return <ClockCircleOutlined style={{ fontSize: "20px" }} />;
    }
  };

  const handleApprove = () => {
    const body = {
      data: {
        type: "job",
        id: jobId,
        attributes: {
          status: "approved",
        },
      },
    };

    axiosInstance
      .patch(`organization/${organizationId}/job/${jobId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Approve successful");
      })
      .catch((error) => {
        message.error("Could not approve: " + error.response.data.errors[0].detail);
      });
  };

  const handleRejected = () => {
    const body = {
      data: {
        type: "job",
        id: jobId,
        attributes: {
          status: "rejected",
        },
      },
    };

    axiosInstance
      .patch(`organization/${organizationId}/job/${jobId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Discard successful");
      })
      .catch((error) => {
        message.error("Could not discard: " + error.response.data.errors[0].detail);
      });
  };

  const sortbyName = (a: JobStep, b: JobStep) => {
    if (a.stepNumber < b.stepNumber) return -1;
    if (a.stepNumber > b.stepNumber) return 1;
    return 0;
  };

  useEffect(() => {
    setLoading(true);
    loadJob();
    loadContext();
    setLoading(false);
    const interval = setInterval(() => {
      loadJob();
      loadContext();
    }, 5000);
    return () => clearInterval(interval);
  }, [jobId]);

  const loadJob = () => {
    const jobSteps: JobStep[] = [];
    axiosInstance.get(`organization/${organizationId}/job/${jobId}?include=step,workspace`).then((response) => {
      (async () => {
        setJob(response.data);
        if (response.data.included != null) {
          for (const element of response.data.included) {
              if (element.type === "step") {
                  const log = await outputLog(element.attributes.output, element.attributes.status);
                  jobSteps.push({
                      id: element.id,
                      stepNumber: element.attributes.stepNumber,
                      status: element.attributes.status,
                      output: element.attributes.output,
                      name: element.attributes.name,
                      outputLog: log,
                  });
              } else if (element.type === "workspace") {
                  setWorkspaceSource(element.attributes.source);
                  setWorkspaceDefaultBranch(element.attributes.branch);

                axiosInstance.get(`organization/${organizationId}/workspace/${element.id}`).then((vcsResponse) => {
                    setWorkspaceVcsId(vcsResponse.data.data.relationships.vcs.data?.id);
                    if (vcsResponse.data.data.relationships.vcs.data?.id) {
                        axiosInstance.get(`organization/${organizationId}/vcs/${vcsResponse.data.data.relationships.vcs.data.id}`).then((vcsDataResponse) => {
                            setWorkspaceVcsName(vcsDataResponse.data.data.attributes.name);
                        });
                    }
                })
              }
          }
        }
        setSteps(jobSteps.sort(sortbyName));
      })();
    });
  };

  const loadContext = () => {
    const api = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL);

    axiosInstance.get(`${api.protocol}//${api.host}/context/v1/${jobId}`).then((response) => {
      if (response?.data?.terrakubeUI) setUITemplates(response?.data?.terrakubeUI);
    });
  };
  return (
    <div style={{ marginTop: "14px" }}>
      {loading || !job?.data || !steps ? (
        <Spin spinning={true} tip="Loading Job...">
          <p style={{ marginTop: "50px" }}></p>
        </Spin>
      ) : (
        <Space direction="vertical" style={{ width: "100%" }}>
          <div>
            <Tag
              icon={
                job.data.attributes.status === "completed" ? (
                  <CheckCircleOutlined />
                ) : job.data.attributes.status === "running" ? (
                  <SyncOutlined spin />
                ) : job.data.attributes.status === "waitingApproval" ? (
                  <ExclamationCircleOutlined />
                ) : job.data.attributes.status === "cancelled" ? (
                  <StopOutlined />
                ) : job.data.attributes.status === "failed" ? (
                  <StopOutlined />
                ) : (
                  <ClockCircleOutlined />
                )
              }
              color={
                job.data.attributes.status === "completed"
                  ? "#2eb039"
                  : job.data.attributes.status === "noChanges"
                    ? "#2eb039"
                    : job.data.attributes.status === "notExecuted"
                      ? "#fa8f37"
                      : job.data.attributes.status === "running"
                        ? "#108ee9"
                        : job.data.attributes.status == "waitingApproval"
                          ? "#fa8f37"
                          : job.data.attributes.status == "rejected"
                            ? "#FB0136"
                            : job.data.attributes.status == "failed"
                              ? "#FB0136"
                              : ""
              }
            >
              {job.data.attributes.status}
            </Tag>{" "}
            <h2 style={{ display: "inline" }}>Triggered via UI</h2>
          </div>

          <Collapse
            items={[
              {
                key: "1",
                label: (
                  <span>
                    <Avatar size="small" shape="square" icon={<UserOutlined />} />{" "}
                    <b>{job.data.attributes.createdBy}</b> triggered a run from {job.data.attributes.via || "UI"}{" "}
                    {DateTime.fromISO(job.data.attributes.createdDate).toRelative()}

                  </span>
                ),
                children: <p>

                    <table>
                        <tbody>
                        <tr>
                            <td>JobId:</td>
                            <td>{ job.data.id }</td>
                        </tr>
                        {workspaceDefaultBranch !== "remote-content" ? (
                            <>
                                <tr>
                                    <td>Workspace source:</td>
                                    <td>{workspaceSource}</td>
                                </tr>
                                <tr>
                                    <td>Workspace default branch:</td>
                                    <td>{workspaceDefaultBranch}</td>
                                </tr>
                                <tr>
                                    <td>Job branch:</td>
                                    <td>{ job.data.attributes.overrideBranch }</td>
                                </tr>
                                <tr>
                                    <td>Commit:</td>
                                    <td>{ job.data.attributes.commitId }</td>
                                </tr>
                                <tr>
                                    <td>VcsId:</td>
                                    <td>{ workspaceVcsId }</td>
                                </tr>
                                <tr>
                                    <td>VcsName:</td>
                                    <td>{ workspaceVcsName }</td>
                                </tr>
                            </>
                        ):(
                            <>
                                <tr>
                                    <td>Using CLI driven workflow</td>
                                </tr>
                            </>
                        )}
                        </tbody>
                    </table>
                </p>,
              },
            ]}
          />
          {steps.length > 0 ? (
            steps.map((item) => (
              <>
                <Collapse
                  style={{ width: "100%" }}
                  defaultActiveKey={item.status === "running" ? ["2"] : []}
                  items={[
                    {
                      key: "2",
                      label: (
                        <span>
                          {getIconStatus(item)}
                          <h3 style={{ display: "inline" }}>
                            &nbsp; {item.name} {item.status}
                          </h3>
                        </span>
                      ),
                      children: (
                        <>
                          {uiTemplates.hasOwnProperty(item.stepNumber) ? (
                            <>
                              <div
                                style={{
                                  textAlign: "right",
                                  padding: "5px",
                                }}
                              >
                                <Radio.Group onChange={onChange} value={uiType} size="small">
                                  <Radio.Button value="structured">Structured</Radio.Button>
                                  <Radio.Button value="console">Console</Radio.Button>
                                </Radio.Group>
                              </div>
                              {uiType === "structured" ? (
                                <div>{parse(uiTemplates[item.stepNumber])}</div>
                              ) : (
                                <TerminalOutput
                                  outputLog={item.outputLog}
                                  stepName={item.name}
                                  isRunning={item.status === "running"}
                                />
                              )}
                            </>
                          ) : (
                            <TerminalOutput
                              outputLog={item.outputLog}
                              stepName={item.name}
                              isRunning={item.status === "running"}
                            />
                          )}
                        </>
                      ),
                    },
                  ]}
                />
              </>
            ))
          ) : (
            <span />
          )}

          {job.data.attributes.status === "waitingApproval" ? (
            <div style={{ margin: "auto", width: "50%", marginTop: "20px" }}>
              <Card
                title={
                  <span style={{ fontSize: "14px" }}>
                    <b>Needs Confirmation:</b> Someone from <b>{job.data.attributes.approvalTeam}</b> must confirm to
                    continue.
                  </span>
                }
              >
                <Space size={20}>
                  <Button icon={<CheckOutlined />} onClick={handleApprove} type="primary">
                    Approve
                  </Button>
                  <Button icon={<CloseOutlined />} onClick={handleRejected} type="primary" danger>
                    Discard
                  </Button>
                  <Button icon={<CommentOutlined />} onClick={handleComingSoon}>
                    Add Comment
                  </Button>
                </Space>
              </Card>
            </div>
          ) : (
            <span />
          )}

          {job.data.attributes.status === "running" || job.data.attributes.status === "pending" ? (
            <div style={{ margin: "auto", width: "50%", marginTop: "20px" }}>
              <Card
                title={
                  <span style={{ fontSize: "14px" }}>
                    <b>Cancelable:</b> You can cancel this job to stop it from executing.
                  </span>
                }
              >
                <Space size={20}>
                  <Button icon={<StopOutlined />} onClick={handleCancel} type="default" danger>
                    Cancel Job
                  </Button>
                  <Button icon={<CommentOutlined />} onClick={handleComingSoon}>
                    Add Comment
                  </Button>
                </Space>
              </Card>
            </div>
          ) : (
            <span />
          )}
        </Space>
      )}
    </div>
  );
};
