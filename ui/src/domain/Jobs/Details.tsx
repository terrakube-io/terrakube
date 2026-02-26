import {
  CheckCircleOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CommentOutlined,
  ExclamationCircleOutlined,
  StopOutlined,
  SyncOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Avatar, Button, Card, Collapse, message, Radio, RadioChangeEvent, Space, Spin, Tag } from "antd";
import { AxiosResponse } from "axios";
import parse from "html-react-parser";
import { DateTime } from "luxon";
import { useCallback, useEffect, useRef, useState } from "react";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { axiosClient } from "../../config/axiosConfig";
import { useAbortController, usePolling } from "../../hooks";
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
  const { getSignal: getJobSignal, abort: abortJobRequests } = useAbortController();
  const { getSignal: getContextSignal, abort: abortContextRequests } = useAbortController();
  const jobRequestRef = useRef(0);
  const contextRequestRef = useRef(0);
  const pollRequestRef = useRef(0);

  const isAbortError = (error: unknown) => {
    return error instanceof Error && (error.name === "AbortError" || error.name === "CanceledError");
  };

  const outputLog = async (output: string | undefined, status: string, signal: AbortSignal) => {
    if (output != null) {
      const apiDomain = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).hostname;
      try {
        if (output.includes(apiDomain)) {
          const response = await axiosInstance.get(output, { signal });
          return response.data;
        }

        const response = await axiosClient.get(output, { signal });
        return response.data;
      } catch {
        return "No logs available";
      }
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
    abortJobRequests();
    abortContextRequests();
  }, [jobId, abortContextRequests, abortJobRequests]);

  const loadJob = useCallback(async () => {
    const requestId = ++jobRequestRef.current;
    const signal = getJobSignal();

    try {
      const response = await axiosInstance.get(`organization/${organizationId}/job/${jobId}?include=step,workspace`, {
        signal,
      });
      if (requestId !== jobRequestRef.current) return;

      setJob(response.data);

      const included = response.data.included ?? [];
      const stepEntries = included.filter((item: any) => item.type === "step");
      const workspaceEntry = included.find((item: any) => item.type === "workspace");

      const stepsPromise = Promise.all(
        stepEntries.map(async (stepItem: any) => ({
          id: stepItem.id,
          stepNumber: stepItem.attributes.stepNumber,
          status: stepItem.attributes.status,
          output: stepItem.attributes.output,
          name: stepItem.attributes.name,
          outputLog: await outputLog(stepItem.attributes.output, stepItem.attributes.status, signal),
        }))
      );

      const workspacePromise = workspaceEntry
        ? (async () => {
            const workspaceResponse = await axiosInstance.get(
              `organization/${organizationId}/workspace/${workspaceEntry.id}`,
              { signal }
            );
            const vcsId = workspaceResponse.data.data.relationships.vcs.data?.id;

            if (!vcsId) {
              return {
                source: workspaceEntry.attributes.source,
                branch: workspaceEntry.attributes.branch,
                vcsId: undefined,
                vcsName: undefined,
              };
            }

            const vcsDataResponse = await axiosInstance.get(`organization/${organizationId}/vcs/${vcsId}`, {
              signal,
            });

            return {
              source: workspaceEntry.attributes.source,
              branch: workspaceEntry.attributes.branch,
              vcsId,
              vcsName: vcsDataResponse.data.data.attributes.name,
            };
          })()
        : Promise.resolve(undefined);

      const [jobSteps, workspaceData] = await Promise.all([stepsPromise, workspacePromise]);
      if (requestId !== jobRequestRef.current) return;

      if (workspaceData) {
        setWorkspaceSource(workspaceData.source);
        setWorkspaceDefaultBranch(workspaceData.branch);
        setWorkspaceVcsId(workspaceData.vcsId);
        setWorkspaceVcsName(workspaceData.vcsName);
      } else {
        setWorkspaceSource(undefined);
        setWorkspaceDefaultBranch(undefined);
        setWorkspaceVcsId(undefined);
        setWorkspaceVcsName(undefined);
      }

      setSteps(jobSteps.sort(sortbyName));
    } catch (error) {
      if (isAbortError(error)) return;
    }
  }, [getJobSignal, jobId, organizationId]);

  const loadContext = useCallback(async () => {
    const requestId = ++contextRequestRef.current;
    const signal = getContextSignal();
    const api = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL);

    try {
      const response = await axiosInstance.get(`${api.protocol}//${api.host}/context/v1/${jobId}`, { signal });
      if (requestId !== contextRequestRef.current) return;
      if (response?.data?.terrakubeUI) {
        setUITemplates(response?.data?.terrakubeUI);
      }
    } catch (error) {
      if (isAbortError(error)) return;
    }
  }, [getContextSignal, jobId]);

  const refreshJobDetails = useCallback(async () => {
    const requestId = ++pollRequestRef.current;
    await Promise.all([loadJob(), loadContext()]);
    if (requestId === pollRequestRef.current) {
      setLoading(false);
    }
  }, [loadContext, loadJob]);

  usePolling(
    () => {
      void refreshJobDetails();
    },
    {
      interval: 5000,
      enabled: Boolean(jobId),
      immediate: true,
    }
  );
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
                    {job.data.attributes.createdDate
                      ? DateTime.fromISO(job.data.attributes.createdDate || "").toRelative()
                      : ""}
                  </span>
                ),
                children: (
                  <p>
                    <table>
                      <tbody>
                        <tr>
                          <td>JobId:</td>
                          <td>{job.data.id}</td>
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
                              <td>{(job.data.attributes as any).overrideBranch}</td>
                            </tr>
                            <tr>
                              <td>Commit:</td>
                              <td>{job.data.attributes.commitId}</td>
                            </tr>
                            <tr>
                              <td>VcsId:</td>
                              <td>{workspaceVcsId}</td>
                            </tr>
                            <tr>
                              <td>VcsName:</td>
                              <td>{workspaceVcsName}</td>
                            </tr>
                          </>
                        ) : (
                          <>
                            <tr>
                              <td>Using CLI driven workflow</td>
                            </tr>
                          </>
                        )}
                      </tbody>
                    </table>
                  </p>
                ),
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
