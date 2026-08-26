import { Button, Tabs, Typography } from "antd";
import { HiOutlineExternalLink } from "react-icons/hi";

type Props = {
  organizationName?: string;
  workspaceName: string;
};
export const CLIDriven = ({ organizationName, workspaceName }: Props) => {
  return (
    <div>
      <Typography.Title level={2} style={{ margin: 0 }}>
        Waiting for configuration
      </Typography.Title>
      <div className="App-text">
        This workspace currently has no Terraform configuration files associated with it. Terrakube is waiting for the
        configuration to be uploaded.
      </div>
      <Typography.Title level={3} style={{ margin: 0 }}>
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
              style={{ marginTop: "30px" }}
              items={[
                {
                  label: "cloud block",
                  key: "1",
                  children: (
                    <pre className="moduleCode">
                      terraform {"{"} <br />
                      &nbsp;&nbsp;cloud {"{"} <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;hostname = "{new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).hostname}"
                      <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;organization = "{organizationName}
                      " <br />
                      <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;workspaces {"{"} <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;name = "{workspaceName}" <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;{"}"} <br />
                      &nbsp;&nbsp;{"}"} <br />
                      {"}"} <br />
                    </pre>
                  ),
                },
                {
                  label: "remote backend",
                  key: "2",
                  children: (
                    <pre className="moduleCode">
                      terraform {"{"} <br />
                      &nbsp;&nbsp;backend "remote" {"{"} <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;hostname = "{new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).hostname}"
                      <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;organization = "{organizationName}
                      " <br />
                      <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;workspaces {"{"} <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;name = "{workspaceName}" <br />
                      &nbsp;&nbsp;&nbsp;&nbsp;{"}"} <br />
                      &nbsp;&nbsp;{"}"} <br />
                      {"}"} <br />
                    </pre>
                  ),
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
        <Typography.Title level={3} style={{ margin: 0 }}>
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
