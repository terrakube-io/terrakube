import { CopyOutlined, DownloadOutlined, ExportOutlined, VerticalAlignBottomOutlined } from "@ant-design/icons";
import { message, Tooltip } from "antd";
import Ansi from "ansi-to-react";
import { useEffect, useRef, useState } from "react";
import { stripAnsi } from "./stripAnsi";
import "./TerminalOutput.css";

type Props = {
  outputLog: string;
  stepName: string;
  isRunning: boolean;
};

export const TerminalOutput = ({ outputLog, stepName, isRunning }: Props) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [followEnabled, setFollowEnabled] = useState(isRunning);

  useEffect(() => {
    if (followEnabled && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [outputLog, followEnabled]);

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
      <div className="terminal-container" ref={containerRef}>
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
        <div className="terminal-content">
          <Ansi>{outputLog}</Ansi>
        </div>
      </div>
    </div>
  );
};
