import { GithubOutlined, GitlabOutlined } from "@ant-design/icons";
import { Breadcrumb, Button, Form, Input, Layout, Select, Space, Steps, message, theme } from "antd";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { SiBitbucket, SiGit } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { Link, useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { SshKey, VcsModel, VcsType } from "../types";
const { Content } = Layout;
const { Step } = Steps;
const validateMessages = {
  required: "${label} is required!",
  types: {
    url: "${label} is not a valid git url",
  },
};

type CreateVcsForm = {
  name: string;
  description: string;
  provider: string;
  source: string;
  folder?: string;
  tagPrefix?: string;
  sshKey?: string;
};

export const CreateModule = () => {
  const {
    token: { colorBgContainer },
  } = theme.useToken();
  const [current, setCurrent] = useState(0);
  const [step3Hidden, setStep3Hidden] = useState(true);
  const [step2Hidden, setStep2Hidden] = useState(true);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [vcs, setVCS] = useState<VcsModel[]>([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const [vcsId, setVcsId] = useState("");
  const [vcsButtonsVisible, setVCSButtonsVisible] = useState(true);
  const [sshKeys, setSSHKeys] = useState<SshKey[]>([]);
  const [sshKeysVisible, setSSHKeysVisible] = useState(false);

  useEffect(() => {
    loadSSHKeys();
    loadVCSProviders();
  }, [organizationId]);

  const handleGitClick = (id: string) => {
    if (id === "git") {
      setSSHKeysVisible(true);
    } else {
      setSSHKeysVisible(false);
      setVcsId(id);
    }
    setCurrent(1);
    setStep2Hidden(false);
  };

  const handleGitContinueClick = () => {
    setCurrent(2);
    setStep3Hidden(false);
    setStep2Hidden(true);
    const source = form.getFieldValue("source");

    if (source != null) {
      const providerValue = source.match("terraform-(.*)-");
      if (providerValue != null && providerValue.length > 0) {
        form.setFieldsValue({ provider: providerValue[1] });
        const nameValue = source.match(providerValue[1] + "-(.*).git");
        if (nameValue != null && nameValue.length > 0) {
          form.setFieldsValue({ name: nameValue[1] });
        }
      }
    }
  };

  const handleVCSClick = (vcsType: VcsType) => {
    navigate(`/organizations/${organizationId}/settings/vcs/new/${vcsType}`);
  };

  const handleDifferent = () => {
    setVCSButtonsVisible(false);
  };

  const handleExisting = () => {
    setVCSButtonsVisible(true);
  };

  const renderVCSLogo = (vcs: VcsType) => {
    switch (vcs) {
      case "GITLAB":
        return <GitlabOutlined style={{ fontSize: "20px" }} />;
      case "BITBUCKET":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <SiBitbucket />
            &nbsp;&nbsp;
          </IconContext.Provider>
        );
      case "AZURE_DEVOPS":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <VscAzureDevops />
            &nbsp;&nbsp;
          </IconContext.Provider>
        );
        case "AZURE_SP_MI":
            return (
                <IconContext.Provider value={{ size: "20px" }}>
                    <VscAzureDevops />
                    &nbsp;&nbsp;
                </IconContext.Provider>
            );
      default:
        return <GithubOutlined style={{ fontSize: "20px" }} />;
    }
  };

  const loadVCSProviders = () => {
    axiosInstance.get(`organization/${organizationId}/vcs`).then((response) => {
      setVCS(response.data.data);
      setLoading(false);
    });
  };

  const loadSSHKeys = () => {
    axiosInstance.get(`organization/${organizationId}/ssh`).then((response) => {
      setSSHKeys(response.data.data);
    });
  };

  const onFinish = (values: CreateVcsForm) => {
    let body: any = {
      data: {
        type: "module",
        attributes: {
          name: values.name,
          description: values.description,
          provider: values.provider,
          source: values.source,
          folder: values.folder != null ? values.folder : null,
          tagPrefix: values.tagPrefix != null ? values.tagPrefix : null,
        },
      },
    };

    if (vcsId !== "") {
      body = {
        data: {
          type: "module",
          attributes: {
            name: values.name,
            description: values.description,
            provider: values.provider,
            source: values.source,
            folder: values.folder != null ? values.folder : null,
            tagPrefix: values.tagPrefix != null ? values.tagPrefix : null,
          },
          relationships: {
            vcs: {
              data: {
                type: "vcs",
                id: vcsId,
              },
            },
          },
        },
      };
    }

    if (values.sshKey) {
      body = {
        data: {
          type: "module",
          attributes: {
            name: values.name,
            description: values.description,
            provider: values.provider,
            source: values.source,
            folder: values.folder != null ? values.folder : null,
            tagPrefix: values.tagPrefix != null ? values.tagPrefix : null,
          },
          relationships: {
            ssh: {
              data: {
                type: "ssh",
                id: values.sshKey,
              },
            },
          },
        },
      };
    }

    axiosInstance
      .post(`organization/${organizationId}/module`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status === 201) {
          navigate(`/organizations/${organizationId}/registry/${response.data.data.id}`);
        }
      })
      .catch((error) => {
        if (error.response) {
          if (error.response.status === 403) {
            message.error(
              <span>
                You are not authorized to create Modules. <br /> Please contact your administrator and request the{" "}
                <b>Manage Modules</b> permission. <br /> For more information, visit the{" "}
                <a
                  target="_blank"
                  href="https://docs.terrakube.io/user-guide/organizations/team-management"
                  rel="noreferrer"
                >
                  Terrakube documentation
                </a>
                .
              </span>
            );
          }
        }
      });
  };

  const [form] = Form.useForm<CreateVcsForm>();

  const handleChange = (currentVal: number) => {
    setCurrent(currentVal);
    if (currentVal === 1) {
      setStep2Hidden(false);
      setStep3Hidden(true);
    }

    if (currentVal === 2) {
      setStep3Hidden(false);
      setStep2Hidden(true);
    }
  };

  return (
    <Content style={{ padding: "0 50px" }}>
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: sessionStorage.getItem(ORGANIZATION_NAME),
          },
          {
            title: <Link to={`/organizations/${organizationId}/registry`}>Modules</Link>,
          },
          {
            title: "New Module",
          },
        ]}
      />

      <div className="site-layout-content" style={{ background: colorBgContainer }}>
        <div className="createWorkspace">
          <h2>Add Module</h2>
          <div className="App-text">
            This module will be created under the current organization, {sessionStorage.getItem(ORGANIZATION_NAME)}.
          </div>
          <Steps direction="horizontal" size="small" current={current} onChange={handleChange}>
            <Step title="Connect to VCS" />
            <Step title="Choose a repository" />
            <Step title="Confirm selection" />
          </Steps>

          {current === 0 && (
            <Space className="chooseType" direction="vertical">
              <h3>Connect to a version control provider</h3>
              <div className="workflowDescription2 App-text">
                Choose the version control provider that hosts your module source code.
              </div>
              {vcsButtonsVisible ? (
                <div>
                  <Space direction="horizontal">
                    <Button
                      icon={<SiGit />}
                      onClick={() => {
                        handleGitClick("git");
                      }}
                      size="large"
                    >
                      &nbsp;Git
                    </Button>
                    {loading || vcs.length === 0 ? (
                      <p>Data loading...</p>
                    ) : (
                      vcs.map(function (item) {
                        return (
                          <Button
                            icon={renderVCSLogo(item.attributes.vcsType)}
                            onClick={() => {
                              handleGitClick(item.id);
                            }}
                            size="large"
                            key={item.id}
                          >
                            &nbsp;{item.attributes.name}
                          </Button>
                        );
                      })
                    )}
                  </Space>
                  <br />
                  <Button onClick={handleDifferent} className="link" type="link">
                    Connect to a different VCS
                  </Button>
                </div>
              ) : (
                <div>
                  <Space direction="horizontal">
                    <Button
                      icon={<GithubOutlined />}
                      onClick={() => {
                        handleVCSClick(VcsType.GITHUB);
                      }}
                      size="large"
                    >
                      Github
                    </Button>
                    <Button
                      icon={<GitlabOutlined />}
                      onClick={() => {
                        handleVCSClick(VcsType.GITLAB);
                      }}
                      size="large"
                    >
                      Gitlab
                    </Button>
                    <Button
                      icon={<SiBitbucket />}
                      onClick={() => {
                        handleVCSClick(VcsType.BITBUCKET);
                      }}
                      size="large"
                    >
                      &nbsp;&nbsp;Bitbucket
                    </Button>
                    <Button
                      icon={<VscAzureDevops />}
                      onClick={() => {
                        handleVCSClick(VcsType.AZURE_DEVOPS);
                      }}
                      size="large"
                    >
                      &nbsp;&nbsp;Azure Devops
                    </Button>
                  </Space>
                  <br />
                  <Button onClick={handleExisting} className="link" type="link">
                    Use an existing VCS connection
                  </Button>
                </div>
              )}
            </Space>
          )}

          <Form
            form={form}
            name="create-module"
            layout="vertical"
            onFinish={onFinish}
            validateMessages={validateMessages}
          >
            <Space hidden={step2Hidden} className="chooseType" direction="vertical">
              <h3>Choose a repository</h3>
              <div className="workflowDescription2 App-text">
                Choose the repository that hosts your module source code. The format of your repository name should be{" "}
                <b>{"terraform-<PROVIDER>-<NAME>"}</b>.
              </div>
              <Form.Item
                name="source"
                label="Git repo"
                tooltip="e.g. https://github.com/Terrakube/terraform-sample-repository.git or git@github.com:AzBuilder/terraform-azurerm-webapp-sample.git"
                extra=" Git repo must be a valid git url using either https or ssh protocol."
                rules={[
                  {
                    required: true,
                    pattern: new RegExp(
                      "((git|ssh|http(s)?)|(git@[\\w\\.]+))(:(//)?)([\\w\\.@\\:/\\-~]+)(\\.git)?(/)?"
                    ),
                  },
                ]}
              >
                <Input />
              </Form.Item>
              <Form.Item>
                <Button onClick={handleGitContinueClick} type="primary">
                  Continue
                </Button>
              </Form.Item>
            </Space>

            <Space hidden={step3Hidden} className="chooseType" direction="vertical">
              <h3>Confirm selection</h3>
              <Form.Item
                name="name"
                label="Module Name"
                rules={[
                  { required: true },
                  {
                    max: 32,
                    message: "Value should be less than 32 character",
                  },
                ]}
                extra="The name of your module generally names the abstraction that the module is intending to create."
              >
                <Input />
              </Form.Item>

              <Form.Item name="description" label="Module Description" rules={[{ required: true }]}>
                <Input.TextArea placeholder="(description)" />
              </Form.Item>
              <Form.Item
                name="provider"
                tooltip="e.g. azurerm,aws,google"
                label="Provider"
                rules={[{ required: true }]}
                extra="The name of a remote system that the module is primarily written to target."
              >
                <Input />
              </Form.Item>
              <Form.Item
                name="tagPrefix"
                label="Tag prefix for modules"
                rules={[{ required: false }]}
                extra="Leave the field empty unless you are using a monorepository configuration. Example vmlinux/"
              >
                <Input />
              </Form.Item>
              <Form.Item
                name="folder"
                label="Folder for the terraform module inside the repository"
                rules={[{ required: false }]}
                extra="Leave the field empty unless you are using a monorepository configuration"
              >
                <Input />
              </Form.Item>
              <Form.Item
                hidden={!sshKeysVisible}
                name="sshKey"
                label="SSH Key"
                tooltip="Select an SSH Key that will be used to clone this repo."
                extra="To use the SSH support in modules the source should be used like git@github.com:AzBuilder/terrakube-docker-compose.git"
                rules={[{ required: false }]}
              >
                <Select placeholder="select SSH Key" style={{ width: 250 }}>
                  {sshKeys.map(function (sshKey) {
                    return <Select.Option key={sshKey?.id}>{sshKey?.attributes?.name}</Select.Option>;
                  })}
                </Select>
              </Form.Item>
              <Form.Item>
                <Button type="primary" htmlType="submit">
                  Publish Module
                </Button>
              </Form.Item>
            </Space>
          </Form>
        </div>
      </div>
    </Content>
  );
};
