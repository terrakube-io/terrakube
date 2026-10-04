import { CopyOutlined } from "@ant-design/icons";
import { Button, message, Tabs, Typography } from "antd";
import { HiOutlineExternalLink } from "react-icons/hi";
import { copyValue } from "@/components/settings/IdField/IdField";
import "./Workspaces.css";

type Props = {
  organizationName?: string;
  workspaceName: string;
};

// A configuration snippet with a copy button, as in the registry "Usage" section.
const Snippet = ({ code, disabled }: { code: string; disabled: boolean }) => (
  <>
    <pre className="cli-snippet">{code}</pre>
    <Button
      icon={<CopyOutlined />}
      disabled={disabled}
      onClick={() =>
        copyValue(code).then(
          () => message.success("Configuration copied"),
          () => message.error("Could not copy the configuration")
        )
      }
    >
      Copy configuration
    </Button>
  </>
);

export const CLIDriven = ({ organizationName, workspaceName }: Props) => {
  const hostname = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).hostname;
  const configBlock = (opener: string) =>
    `terraform {
  ${opener}
    hostname     = "${hostname}"
    organization = "${organizationName ?? ""}"

    workspaces {
      name = "${workspaceName}"
    }
  }
}`;

  return (
    <div>
      <Typography.Title level={2} className="workspace-flush-title">
        Waiting for configuration
      </Typography.Title>
      <div className="App-text">
        This workspace currently has no Terraform configuration files associated with it. Terrakube is waiting for the
        configuration to be uploaded.
      </div>
      <Typography.Title level={3} className="workspace-flush-title">
        CLI-driven workflow
      </Typography.Title>
      <div className="App-text">
        <ol>
          <li>
            Ensure you are properly authenticated into Terrakube by running{" "}
            <span className="code">terraform login</span> on the command line or by using a credentials block.
          </li>{" "}
          <br />
          <li>
            Add a code block to your Terraform configuration files to set up the remote backend . You can add this
            configuration block to any .tf file in the directory where you run Terraform. <br />
            <br />
            <b>Example Code</b>
            <Tabs
              type="card"
              className="cli-snippet-tabs"
              items={[
                {
                  label: "cloud block",
                  key: "1",
                  children: <Snippet code={configBlock("cloud {")} disabled={!organizationName} />,
                },
                {
                  label: "remote backend",
                  key: "2",
                  children: <Snippet code={configBlock('backend "remote" {')} disabled={!organizationName} />,
                },
              ]}
            />
          </li>
          <br />
          <li>
            Run <span className="code">terraform init</span> to initialize the workspace.
          </li>
          <br />
          <li>
            Run <span className="code">terraform apply</span> to start the first run for this workspace.
          </li>
        </ol>
        For more details, see the{" "}
        <Button
          className="link"
          target="_blank"
          href="https://docs.terrakube.io/user-guide/workspaces/cli-driven-workflow"
          type="link"
        >
          CLI workflow guide.&nbsp; <HiOutlineExternalLink />.
        </Button>
        <br /> <br />
        <Typography.Title level={3} className="workspace-flush-title">
          API-driven workflow
        </Typography.Title>
        Advanced users can follow{" "}
        <Button
          className="link"
          target="_blank"
          href="https://docs.terrakube.io/user-guide/workspaces/api-driven-workflow"
          type="link"
        >
          this guide.&nbsp; <HiOutlineExternalLink />.
        </Button>{" "}
        to set up their workspace.
      </div>
    </div>
  );
};
