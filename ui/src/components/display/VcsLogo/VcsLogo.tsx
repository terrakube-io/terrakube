import { GitlabOutlined, GithubOutlined } from "@ant-design/icons";
import { IconContext } from "react-icons";
import { SiBitbucket, SiGit } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { VcsType } from "@/domain/types";
import "./VcsLogo.css";

type Props = {
  type?: VcsType;
  size?: number;
};

export default function VcsLogo({ type, size = 18 }: Props) {
  // size is a prop, so it reaches the stylesheet as a custom property.
  const sizeVar = { "--vcs-logo-size": `${size}px` } as React.CSSProperties;
  switch (type) {
    case VcsType.GITLAB:
      return <GitlabOutlined className="vcs-logo-icon" style={sizeVar} />;
    case VcsType.BITBUCKET:
      return (
        <IconContext.Provider value={{ size: `${size}px` }}>
          <SiBitbucket />
          &nbsp;
        </IconContext.Provider>
      );
    case VcsType.AZURE_DEVOPS:
      return (
        <IconContext.Provider value={{ size: `${size}px` }}>
          <VscAzureDevops />
          &nbsp;
        </IconContext.Provider>
      );
    case VcsType.GITHUB:
      return <GithubOutlined className="vcs-logo-icon" style={sizeVar} />;

    default:
      return <SiGit size={size} />;
  }
}
