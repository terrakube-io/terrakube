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
import { Alert, Avatar, Button, Card, Collapse, message, Radio, RadioChangeEvent, Space, Spin, Tag, Typography } from "antd";
import { AxiosResponse } from "axios";
import parse from "html-react-parser";
import { DateTime } from "luxon";
import { useCallback, useEffect, useRef, useState } from "react";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { axiosClient } from "../../config/axiosConfig";
import { useAbortController, usePolling } from "../../hooks";
import { Job, JobStep } from "../types";
import { TerminalOutput } from "./TerminalOutput";
import { getJobOutputRequestUrl, getPublicApiOrigin, isTerrakubeApiUrl } from "./outputUrl";
import { shouldStepBeCollapsible, shouldStepBeExpandedByDefault } from "./stepExpansion";
import { StructuredPlanOutput } from "./StructuredPlanOutput";
import { StructuredPlanOutputByStep, normalizeStructuredPlanOutput, normalizeUITemplates } from "./structuredPlan";

type Props = {
  jobId: string;
};

const TERMINAL_JOB_STATUSES = new Set(["completed", "noChanges", "failed", "cancelled", "rejected", "notExecuted"]);
const INCOMPLETE_VARIABLE_GUARD_STEP_NAME = "Incomplete sensitive variables";

type IncompleteVariableGuard = {
  title: string;
  variables: string[];
  footer?: string;
  rawMessage: string;
};

export const DetailsJob = ({ jobId }: Props) => {
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [loading, setLoading] = useState(false);
  const [job, setJob] = useState<AxiosResponse<Job>>();
  const [workspaceSource, setWorkspaceSource] = useState<string>();
  const [workspaceDefaultBranch, setWorkspaceDefaultBranch] = useState<string>();
  const [workspaceVcsId, setWorkspaceVcsId] = useState<string>();
  const [workspaceVcsName, setWorkspaceVcsName] = useState<string>();
  const [steps, setSteps] = useState<JobStep[]>([]);
  const [uiType, setUIType] = useState("structured");
  const [uiTemplates, setUITemplates] = useState<Record<string, string>>({});
  const [planStructuredOutput, setPlanStructuredOutput] = useState<StructuredPlanOutputByStep>({});
  const { getSignal: getJobSignal, abort: abortJobRequests } = useAbortController();
  const { getSignal: getContextSignal, abort: abortContextRequests } = useAbortController();
  const jobRequestRef = useRef(0);
  const contextRequestRef = useRef(0);
  const pollRequestRef = useRef(0);

  const isAbortError = (error: unknown) => {
    return error instanceof Error && (error.name === "AbortError" || error.name === "CanceledError");
  };

  const isTerminalJobStatus = (status?: string) => {
    if (!status) {
      return false;
    }

    return TERMINAL_JOB_STATUSES.has(status);
  };

  const parseIncompleteVariableGuard = (jobOutput?: string): IncompleteVariableGuard | null => {
    if (jobOutput == null) {
      return null;
    }

    const lines = jobOutput
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line !== "");

    if (lines.length === 0) {
      return null;
    }

    const variables = lines
      .filter((line) => line.startsWith("- "))
      .map((line) => line.slice(2).trim())
      .filter((line) => line !== "");

    const footer = lines.find((line) => line.startsWith("Open the workspace Variables page"));

    if (variables.length === 0 || footer == null) {
      return null;
    }

    return {
      title: lines[0],
      variables,
      footer,
      rawMessage: jobOutput,
    };
  };

  const isIncompleteVariableGuardStep = (stepName?: string) => {
    return stepName === INCOMPLETE_VARIABLE_GUARD_STEP_NAME;
  };

  const outputLog = async (output: string | undefined, status: string, signal: AbortSignal) => {
    if (output != null) {
      const outputUrl = getJobOutputRequestUrl(output);

      try {
        if (isTerrakubeApiUrl(outputUrl)) {
          const response = await axiosInstance.get(outputUrl, { signal });
          return response.data;
        }

        const response = await axiosClient.get(outputUrl, { signal });
        return response.data;
      } catch {
        return "No logs available";
      }
    } else {
      if (status === "running") return "Initializing the backend...";
      else return "Waiting logs...";
    }
  };

  const renderIncompleteVariableAlert = (guard: IncompleteVariableGuard) => {
    return (
      <Alert
        type="error"
        showIcon
        message="Run stopped before execution"
        description={
          <Space direction="vertical" size="small" style={{ width: "100%" }}>
            <Typography.Text>{guard.title}</Typography.Text>
            {guard.variables.length > 0 && (
              <Space size={[8, 8]} wrap>
                {guard.variables.map((variable) => {
                  return (
                    <Tag key={variable} color="orange">
                      {variable}
                    </Tag>
                  );
                })}
              </Space>
            )}
            {guard.footer != null && <Typography.Text type="secondary">{guard.footer}</Typography.Text>}
          </Space>
        }
      />
    );
  };

  const handleComingSoon = () => {
    message.info("Coming Soon!");
  };

  const onChange = (e: RadioChangeEvent) => {
    setUIType(e.target.value);
  };

  const renderConsoleOutput = (item: JobStep) => {
    return <TerminalOutput outputLog={item.outputLog} stepName={item.name} isRunning={item.status === "running"} />;
  };

  const renderStepContent = (item: JobStep) => {
    const guard = parseIncompleteVariableGuard(job?.data?.attributes.output);

    if (guard != null && isIncompleteVariableGuardStep(item.name)) {
      return renderConsoleOutput(item);
    }

    const template = uiTemplates[item.id] || uiTemplates[String(item.stepNumber)];
    const structuredChanges = planStructuredOutput[item.id] || planStructuredOutput[String(item.stepNumber)];
    const hasStructuredView = Boolean(template) || Boolean(structuredChanges);

    if (!hasStructuredView) {
      return renderConsoleOutput(item);
    }

    if (uiType !== "structured") {
      return (
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
          {renderConsoleOutput(item)}
        </>
      );
    }

    let structuredContent = renderConsoleOutput(item);

    if (structuredChanges) {
      structuredContent = <StructuredPlanOutput changes={structuredChanges} outputLog={item.outputLog} />;
    } else if (template) {
      structuredContent = <div>{parse(template)}</div>;
    }

    return (
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
        {structuredContent}
      </>
    );
  };

  const renderStepLabel = (item: JobStep) => {
    return (
      <span>
        {getIconStatus(item)}
        <h3 style={{ display: "inline" }}>
          &nbsp; {item.name} {item.status}
        </h3>
      </span>
    );
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

  const loadJob = useCallback(async () => {
    const requestId = ++jobRequestRef.current;
    const signal = getJobSignal();

    try {
      const response = await axiosInstance.get(`organization/${organizationId}/job/${jobId}?include=step,workspace`, {
        signal,
      });
      if (requestId !== jobRequestRef.current) {
        return;
      }

      setJob(response.data);

      const included = response.data.included ?? [];
      const stepEntries = included.filter((item: any) => item.type === "step");
      const workspaceEntry = included.find((item: any) => item.type === "workspace");
      const incompleteVariableGuard = parseIncompleteVariableGuard(response.data.data.attributes.output);

      const stepsPromise = Promise.all(
        stepEntries.map(async (stepItem: any) => ({
          id: stepItem.id,
          stepNumber: stepItem.attributes.stepNumber,
          status: stepItem.attributes.status,
          output: stepItem.attributes.output,
          name: stepItem.attributes.name,
          outputLog:
            incompleteVariableGuard != null && isIncompleteVariableGuardStep(stepItem.attributes.name)
              ? incompleteVariableGuard.rawMessage
              : await outputLog(stepItem.attributes.output, stepItem.attributes.status, signal),
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
      if (requestId !== jobRequestRef.current) {
        return;
      }

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
    const apiOrigin = getPublicApiOrigin();

    try {
      const response = await axiosInstance.get(`${apiOrigin}/context/v1/${jobId}`, { signal });
      if (requestId !== contextRequestRef.current) {
        return;
      }
      setUITemplates(normalizeUITemplates(response?.data?.terrakubeUI));
      setPlanStructuredOutput(normalizeStructuredPlanOutput(response?.data?.planStructuredOutput));
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

  useEffect(() => {
    setLoading(true);
    abortJobRequests();
    abortContextRequests();

    if (!jobId) {
      setLoading(false);
      return;
    }

    void refreshJobDetails();
  }, [abortContextRequests, abortJobRequests, jobId, refreshJobDetails]);

  usePolling(
    () => {
      void refreshJobDetails();
    },
    {
      interval: 5000,
      enabled: Boolean(jobId) && !isTerminalJobStatus(job?.data?.attributes.status),
      immediate: false,
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
          {(() => {
            const guard = parseIncompleteVariableGuard(job.data.attributes.output);

            if (guard == null) {
              return null;
            }

            return renderIncompleteVariableAlert(guard);
          })()}
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
            steps.map((item) => {
              const stepKey = `${item.id}-${item.status}`;
              const stepLabel = renderStepLabel(item);

              if (!shouldStepBeCollapsible(item)) {
                return (
                  <Card key={stepKey} size="small" style={{ width: "100%" }}>
                    {stepLabel}
                  </Card>
                );
              }

              return (
                <Collapse
                  key={stepKey}
                  style={{ width: "100%" }}
                  defaultActiveKey={shouldStepBeExpandedByDefault(item) ? ["2"] : []}
                  items={[
                    {
                      key: "2",
                      label: stepLabel,
                      children: renderStepContent(item),
                    },
                  ]}
                />
              );
            })
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
