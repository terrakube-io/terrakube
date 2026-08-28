import { useCallback, useMemo, useState } from "react";
import axiosInstance from "../../config/axiosConfig";
import { useLogStream, usePolling } from "../../hooks";
import { splitLines } from "./ansiChunks";
import { getPublicApiOrigin } from "./outputUrl";
import { isRunningStatus } from "./stepStatus";
import { TerminalOutput } from "./TerminalOutput";
import { JobStep } from "../types";

type Props = {
  jobId: string;
  organizationId: string;
  item: JobStep;
};

// Matches the API's io.terrakube.logs.live-tail-records default: while a step streams, keep only the
// last N lines in memory (that's what people watch during an apply); the full log is fetched from
// storage once the step is terminal.
const LIVE_TAIL_LINES = 5000;
const POLL_INTERVAL_MS = 3000;

export const LiveTerminalOutput = ({ jobId, organizationId, item }: Props) => {
  const isRunning = isRunningStatus(item.status);
  const stepBaseUrl = `${getPublicApiOrigin()}/tfoutput/v1/organization/${organizationId}/job/${jobId}/step/${item.id}`;

  const [sseFailed, setSseFailed] = useState(false);
  const onStatus = useCallback((status: { failed: boolean }) => setSseFailed(status.failed), []);

  const { text } = useLogStream({ url: `${stepBaseUrl}/stream`, enabled: isRunning, onStatus });

  const [polledText, setPolledText] = useState("");
  usePolling(
    async () => {
      try {
        const response = await axiosInstance.get(stepBaseUrl, { responseType: "text", transformResponse: (d) => d });
        setPolledText(typeof response.data === "string" ? response.data : String(response.data ?? ""));
      } catch {
        // keep whatever we last had
      }
    },
    { interval: POLL_INTERVAL_MS, enabled: isRunning && sseFailed, immediate: true }
  );

  const liveText = sseFailed ? polledText : text;

  const { tailed, truncated } = useMemo(() => {
    const lines = splitLines(liveText);
    if (lines.length > LIVE_TAIL_LINES) {
      return { tailed: lines.slice(-LIVE_TAIL_LINES).join("\n"), truncated: true };
    }
    return { tailed: liveText, truncated: false };
  }, [liveText]);

  return (
    <TerminalOutput
      outputLog={tailed}
      stepName={item.name}
      isRunning={isRunning}
      emptyLabel="Waiting for output…"
      truncated={truncated}
    />
  );
};
