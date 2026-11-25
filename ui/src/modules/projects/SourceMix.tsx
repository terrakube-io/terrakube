import { Tooltip } from "antd";
import { LockOutlined } from "@ant-design/icons";
import "./SourceMix.css";

/**
 * How a project's workspaces run: from a repository (VCS) or driven from the CLI or API, as one split bar with the
 * counts beside it, and a chip for the locked ones.
 */
export function SourceMix({ vcs, cli, locked }: { vcs: number; cli: number; locked: number }) {
  const total = vcs + cli;
  const share = (count: number) => (total === 0 ? 0 : (count / total) * 100);
  return (
    <div className="source-mix">
      <span className="source-mix-title">How they run</span>
      <div
        className="source-mix-bar"
        role="img"
        aria-label={`${vcs} run from a repository, ${cli} driven from the CLI or API`}
      >
        <span className="source-mix-vcs" style={{ width: `${share(vcs)}%` }} />
        <span className="source-mix-cli" style={{ width: `${share(cli)}%` }} />
      </div>
      <ul className="source-mix-legend">
        <li>
          <span className="source-mix-swatch source-mix-vcs" />
          {vcs} VCS
        </li>
        <li>
          {/* A CLI workspace on local execution runs on the user's machine and only uploads its state. */}
          <Tooltip title="Driven from the CLI or API. On local execution they only upload state, so Terrakube has no run status for them.">
            <span>
              <span className="source-mix-swatch source-mix-cli" />
              {cli} CLI
            </span>
          </Tooltip>
        </li>
        {locked > 0 && (
          <li className="source-mix-locked">
            <LockOutlined /> {locked} locked
          </li>
        )}
      </ul>
    </div>
  );
}
