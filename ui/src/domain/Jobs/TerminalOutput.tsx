import { CopyOutlined, DownloadOutlined, ExportOutlined, ReloadOutlined, VerticalAlignBottomOutlined } from "@ant-design/icons";
import { message, Tooltip } from "antd";
import { useEffect, useState } from "react";
import { useAnsiLines } from "./ansiChunks";
import { LogViewport } from "./LogViewport";
import { stripAnsi } from "./stripAnsi";
import "./TerminalOutput.css";

type Props = {
  outputLog: string;
  stepName: string;
  isRunning: boolean;
  truncated?: boolean;
  emptyLabel?: string;
  error?: boolean;
  onRetry?: () => void;
};

export const TerminalOutput = ({
  outputLog,
  stepName,
  isRunning,
  truncated = false,
  emptyLabel = "(no output)",
  error = false,
  onRetry,
}: Props) => {
  const [followEnabled, setFollowEnabled] = useState(true);

  useEffect(() => {
    if (isRunning) {
      setFollowEnabled(true);
    }
  }, [isRunning]);

  const lines = useAnsiLines(outputLog);
  const isEmpty = !error && outputLog.length === 0;

  const handleCopy = () => {
    navigator.clipboard.writeText(stripAnsi(outputLog)).then(
      () => message.success("Copied to clipboard"),
      () => message.error("Failed to copy to clipboard")
    );
  };

  const handleDownload = () => {
    const blob = new Blob([stripAnsi(outputLog)], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${stepName}.log`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleRaw = () => {
    const blob = new Blob([stripAnsi(outputLog)], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    window.open(url, "_blank");
    URL.revokeObjectURL(url);
  };

  return (
    <div className="terminal-wrapper">
      <div className="terminal-container">
        <div className="terminal-toolbar">
          <Tooltip title="Auto-scroll to bottom">
            <button
              type="button"
              className={`terminal-toolbar-btn ${followEnabled ? "terminal-toolbar-btn--active" : ""}`}
              onClick={() => setFollowEnabled((prev) => !prev)}
            >
              <VerticalAlignBottomOutlined /> Follow
            </button>
          </Tooltip>
          <Tooltip title="Copy to clipboard">
            <button type="button" className="terminal-toolbar-btn" onClick={handleCopy}>
              <CopyOutlined /> Copy
            </button>
          </Tooltip>
          <Tooltip title="Download as .log file">
            <button type="button" className="terminal-toolbar-btn" onClick={handleDownload}>
              <DownloadOutlined /> Download
            </button>
          </Tooltip>
          <Tooltip title="Open plain text in new tab">
            <button type="button" className="terminal-toolbar-btn" onClick={handleRaw}>
              Raw <ExportOutlined />
            </button>
          </Tooltip>
        </div>

        {truncated && (
          <div className="terminal-notice">
            Earlier output truncated — use Download for the full log.
          </div>
        )}

        {error ? (
          <div className="terminal-notice terminal-notice--error">
            <span>Could not load this step&rsquo;s log.</span>
            {onRetry && (
              <button type="button" className="terminal-toolbar-btn" onClick={onRetry}>
                <ReloadOutlined /> Retry
              </button>
            )}
          </div>
        ) : isEmpty ? (
          <div className="terminal-content terminal-content--empty">{emptyLabel}</div>
        ) : (
          <LogViewport
            className="terminal-content terminal-viewport"
            lines={lines}
            followTail={followEnabled}
            onFollowChange={setFollowEnabled}
          />
        )}
      </div>
    </div>
  );
};
