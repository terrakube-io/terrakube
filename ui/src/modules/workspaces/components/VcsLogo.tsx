import { GitlabOutlined, GithubOutlined } from "@ant-design/icons";
import { IconContext } from "react-icons";
import { SiBitbucket, SiGit } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { VcsType } from "../../../domain/types";

type Props = {
  type?: VcsType;
  size?: number;
};

export default function VcsLogo({ type, size = 18 }: Props) {
  switch (type) {
    case VcsType.GITLAB:
      return <GitlabOutlined style={{ fontSize: `${size}px` }} />;
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
      return <GithubOutlined style={{ fontSize: `${size}px` }} />;

    default:
      return <SiGit size={size} />;
  }
}
