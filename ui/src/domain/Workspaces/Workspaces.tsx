import { GithubOutlined, GitlabOutlined } from "@ant-design/icons";
import { message } from "antd";
import { IconContext } from "react-icons";
import { SiBitbucket, SiTerraform } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import axiosInstance from "../../config/axiosConfig";
import { VcsType } from "../types";

export const genericHeader = {
  headers: {
    "Content-Type": "application/vnd.api+json",
  },
};

export function compareVersions(a: string, b: string) {
  if (a === b) {
    return 0;
  }
  const splitA = a
    .replace("v", "")
    .split(".")
    .map((x) => parseInt(x));
  const splitB = b
    .replace("v", "")
    .split(".")
    .map((x) => parseInt(x));
  const length = Math.max(splitA.length, splitB.length);
  for (let i = 0; i < length; i++) {
    if (splitA[i] > splitB[i] || (splitA[i] === splitB[i] && isNaN(splitB[i + 1]))) {
      return 1;
    }
    if (splitA[i] < splitB[i] || (splitA[i] === splitB[i] && isNaN(splitA[i + 1]))) {
      return -1;
    }
  }
  // TODO: Check
  return -2;
}

export const renderVCSLogo = (vcs: VcsType) => {
  switch (vcs) {
    case "GITLAB":
      return <GitlabOutlined style={{ fontSize: "18px" }} />;
    case "BITBUCKET":
      return (
        <IconContext.Provider value={{ size: "18px" }}>
          <SiBitbucket />
          &nbsp;
        </IconContext.Provider>
      );
    case "AZURE_DEVOPS":
      return (
        <IconContext.Provider value={{ size: "18px" }}>
          <VscAzureDevops />
          &nbsp;
        </IconContext.Provider>
      );
    default:
      return <GithubOutlined style={{ fontSize: "18px" }} />;
  }
};

export const atomicHeader = {
  headers: {
    "content-type": 'application/vnd.api+json;ext="https://jsonapi.org/ext/atomic"',
    accept: 'application/vnd.api+json;ext="https://jsonapi.org/ext/atomic"',
  },
};

// This should be re-written to handle only one webhook
export const deleteWebhook = (organizationId: string, workspaceId: string, webhook: any) => {
  const webhooks = Object.entries(webhook);
  if (webhooks.length == 0) return;
  const body = {
    "atomic:operations": [],
  };
  webhooks.map(([_, hook]: any) => {
    const d: unknown = {
      op: "remove",
      href: `/organization/${organizationId}/workspace/${workspaceId}/webhook/${hook.id}`,
    };
    (body["atomic:operations"] as any).push(d);
  });
  try {
    axiosInstance.post("/operations", body, atomicHeader).then((response) => {
      if (response.status == 200) {
        message.success("Webhook deleted successfully");
      } else {
        message.error("Webhook deletion failed");
      }
    });
  } catch (error: any) {
    console.error("Error deleting webhook:", error);
    message.error("Webhook deletion failed");
    if (error.response) {
      if (error.response.status === 424) {
        message.error(
          "Failed to delete webhook, please check if the set VCS connection has the correct permissions on the linked repository."
        );
      }
    }
  }
};

export const getIaCIconById = (id: string) => {
  const item = iacTypes.find((iacType) => iacType.id === id);
  return item ? item.icon : null;
};
export const getIaCNameById = (id: string) => {
  const item = iacTypes.find((iacType) => iacType.id === id);
  return item ? item.name : null;
};

export const iacTypes = [
  {
    id: "terraform",
    name: "Terraform",
    description: "Create an empty template. So you can define your template from scratch.",
    icon: (
      <IconContext.Provider value={{ size: "1.4em" }}>
        <SiTerraform />
      </IconContext.Provider>
    ),
  },
  {
    id: "tofu",
    name: "OpenTofu",
    icon: <img width="18px" src="/providers/opentofu.png" alt="OpenTofu" />,
  },
];
