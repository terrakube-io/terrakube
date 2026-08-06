import { useLogStream } from "../../hooks";
import { getPublicApiOrigin } from "./outputUrl";
import { TerminalOutput } from "./TerminalOutput";
import { JobStep } from "../types";

type Props = {
  jobId: string;
  organizationId: string;
  item: JobStep;
};

export const LiveTerminalOutput = ({ jobId, organizationId, item }: Props) => {
  const isRunning = item.status === "running";
  const streamUrl = `${getPublicApiOrigin()}/tfoutput/v1/organization/${organizationId}/job/${jobId}/step/${item.id}/stream`;

  const { text } = useLogStream({ url: streamUrl, enabled: isRunning });

  const outputLog = isRunning && text.length > 0 ? text : item.outputLog;

  return <TerminalOutput outputLog={outputLog} stepName={item.name} isRunning={isRunning} />;
};
