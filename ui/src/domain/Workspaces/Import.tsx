import { DeleteOutlined, DownOutlined, GithubOutlined, GitlabOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Breadcrumb,
  Button,
  Card,
  Dropdown,
  Form,
  Input,
  Layout,
  List,
  Modal,
  Progress,
  Select,
  Space,
  Spin,
  Steps,
  Table,
  Typography,
  message,
  theme,
  Alert,
} from "antd";
import parse from "html-react-parser";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { BiBookBookmark, BiTerminal, BiUpload } from "react-icons/bi";
import { SiBitbucket } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { Link, useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { VcsModel, VcsType, VcsTypeExtended } from "../types";
const { Content } = Layout;
const validateMessages = {
  required: "${label} is required!",
  types: {
    url: "${label} is not a valid git url",
  },
};

type Platform = {
  id: string;
  name: string;
  description?: string;
  icon?: string;
  height?: string;
};

type WorkspaceRecord = {
  id: string;
  attributes: {
    name: string;
    description?: string;
    "terraform-version": string;
    "execution-mode"?: string;
    "working-directory"?: string;
    "vcs-repo"?: {
      branch?: string;
      identifier?: string;
      "service-provider"?: string;
      "repository-http-url"?: string;
    };
  };
};

type CollectionOption = {
  id: string;
  name: string;
};

type CollectionApiRecord = {
  id: string;
  attributes: {
    name: string;
  };
};

type WorkspaceVarset = {
  id: string;
  name: string;
};

type WorkspaceMappingRow = {
  key: string;
  sourceName: string;
  terrakubeCollectionId?: string;
  isAdditional: boolean;
};

type ImportProgressItem = {
  id: string;
  name: string;
  status: string;
};

type WorkspaceMappingLoadResult = {
  mappings: Record<string, WorkspaceMappingRow[]>;
  sensitiveVariables: Record<string, SensitiveVariableDraft[]>;
  failedCollectionWorkspaceNames: string[];
  failedSensitiveVariableWorkspaceNames: string[];
};

type SensitiveVariablePreview = {
  id: string;
  key: string;
  description?: string;
  category: string;
  hcl: boolean;
};

type SensitiveVariableDraft = SensitiveVariablePreview & {
  value: string;
};

const NONE_COLLECTION_VALUE = "__none__";
const WORKSPACE_IMPORT_PREVIEW_CONCURRENCY = 4;

const mapWithConcurrency = async <T, TResult>(
  items: T[],
  concurrency: number,
  mapper: (item: T, index: number) => Promise<TResult>
): Promise<TResult[]> => {
  if (items.length === 0) {
    return [];
  }

  const workerCount = Math.min(Math.max(concurrency, 1), items.length);
  const results = new Array<TResult>(items.length);
  let nextIndex = 0;

  const runWorker = async () => {
    while (true) {
      const currentIndex = nextIndex;
      nextIndex += 1;

      if (currentIndex >= items.length) {
        return;
      }

      results[currentIndex] = await mapper(items[currentIndex], currentIndex);
    }
  };

  const workers: Promise<void>[] = [];

  for (let workerIndex = 0; workerIndex < workerCount; workerIndex += 1) {
    workers.push(runWorker());
  }

  await Promise.all(workers);
  return results;
};

export const ImportWorkspace = () => {
  const {
    token: { colorBgContainer },
  } = theme.useToken();
  const [organizationName, setOrganizationName] = useState<string>();
  const [vcs, setVCS] = useState<VcsModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [vcsButtonsVisible, setVCSButtonsVisible] = useState(true);
  const [vcsId, setVcsId] = useState("");
  const [workspaces, setWorkspaces] = useState<WorkspaceRecord[]>([]);
  const [workspacesHidden, setWorkspacesHidden] = useState(true);
  const [workspacesLoading, setWorkspacesLoading] = useState(false);
  const [apiUrlHidden, setApiUrlHidden] = useState(true);
  const [selectedWorkspaces, setSelectedWorkspaces] = useState<WorkspaceRecord[]>([]);
  const [importProgress, setImportProgress] = useState<ImportProgressItem[]>([]);
  const [availableCollections, setAvailableCollections] = useState<CollectionOption[]>([]);
  const [workspaceMappings, setWorkspaceMappings] = useState<Record<string, WorkspaceMappingRow[]>>({});
  const [workspaceSensitiveVariables, setWorkspaceSensitiveVariables] = useState<
    Record<string, SensitiveVariableDraft[]>
  >({});
  const [mappingModalOpen, setMappingModalOpen] = useState(false);
  const [mappingDataLoading, setMappingDataLoading] = useState(false);
  const [mappingTransitionLoading, setMappingTransitionLoading] = useState(false);
  const [currentMappingWorkspaceIndex, setCurrentMappingWorkspaceIndex] = useState(0);
  const [stepsHidden, setStepsHidden] = useState(false);
  const [listHidden, setListHidden] = useState(true);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const columns = [
    {
      title: "Name",
      dataIndex: ["attributes", "name"],
      sorter: {
        compare: (a: WorkspaceRecord, b: WorkspaceRecord) => a.attributes.name.localeCompare(b.attributes.name),
        multiple: 1,
      },
    },
    {
      title: "Terraform Version",
      dataIndex: ["attributes", "terraform-version"],
      sorter: {
        compare: (a: WorkspaceRecord, b: WorkspaceRecord) =>
          a.attributes["terraform-version"].localeCompare(b.attributes["terraform-version"]),
        multiple: 2,
      },
    },
    {
      title: "Vcs Provider",
      dataIndex: ["attributes", "vcs-repo", "service-provider"],
      sorter: {
        compare: (a: WorkspaceRecord, b: WorkspaceRecord) => {
          const vcsRepoA = a.attributes["vcs-repo"];
          const vcsRepoB = b.attributes["vcs-repo"];
          const serviceProviderA = vcsRepoA && vcsRepoA["service-provider"] ? vcsRepoA["service-provider"] : "";
          const serviceProviderB = vcsRepoB && vcsRepoB["service-provider"] ? vcsRepoB["service-provider"] : "";
          return serviceProviderA.localeCompare(serviceProviderB);
        },
        multiple: 2,
      },
    },
    {
      title: "Vcs Identifier",
      dataIndex: ["attributes", "vcs-repo", "identifier"],
    },
  ];
  const [current, setCurrent] = useState(0);
  const [step3Hidden, setStep4Hidden] = useState(true);
  const [versionControlFlow, setVersionControlFlow] = useState(true);
  const [platforms, setPlatforms] = useState<Platform[]>([]);
  const [platform, setPlatform] = useState<Platform>({
    id: "tfcloud",
    name: "Terraform Cloud",
  });
  const stepItems = [
    { title: "Choose Platform" },
    { title: "Choose Type" },
    ...(versionControlFlow ? [{ title: "Connect to VCS" }] : []),
    { title: "Connect to Platform" },
    { title: "Import Workspaces" },
  ];
  const gitlabItems = [
    {
      label: "Gitlab.com",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB);
      },
    },
    {
      label: "Gitlab Community Edition",
      key: "2",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB_COMMUNITY);
      },
    },
    {
      label: "Gitlab Enterprise Edition",
      key: "3",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB_ENTERPRISE);
      },
    },
  ];

  const githubItems = [
    {
      label: "Github.com",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB);
      },
    },
    {
      label: "Github Enterprise",
      key: "2",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB_ENTERPRISE);
      },
    },
  ];

  const bitBucketItems = [
    {
      label: "Bitbucket Cloud",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.BITBUCKET);
      },
    },
  ];

  const azDevOpsItems = [
    {
      label: "Azure DevOps Services",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.AZURE_DEVOPS);
      },
    },
  ];
  const navigate = useNavigate();
  useEffect(() => {
    setOrganizationName(sessionStorage.getItem(ORGANIZATION_NAME) ?? undefined);
    setLoading(true);
    loadVCS();
    getPlatforms();
  }, []);
  const handleClick = () => {
    setCurrent(2);
    setVersionControlFlow(true);
    form.setFieldsValue({ source: "", branch: "" });
  };

  const handlePlatformClick = (platform: Platform) => {
    setCurrent(1);
    setPlatform(platform);
    if (platform.id === "tfcloud") {
      form.setFieldsValue({ apiUrl: "https://app.terraform.io/api/v2" });
      setApiUrlHidden(true);
    } else {
      setApiUrlHidden(false);
      form.setFieldsValue({ apiUrl: "" });
    }
  };

  const handleGitClick = (id: string) => {
    setVcsId(id);
    handleChange(3);
  };

  const handleVCSClick = (vcsType: VcsTypeExtended) => {
    navigate(`/organizations/${organizationId}/settings/vcs/new/${vcsType}`);
  };

  const handleConnectDifferent = () => {
    setVCSButtonsVisible(false);
  };

  const handleConnectExisting = () => {
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
            &nbsp;
          </IconContext.Provider>
        );
      case "AZURE_SP_MI":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <VscAzureDevops />
            &nbsp;
          </IconContext.Provider>
        );
      default:
        return <GithubOutlined style={{ fontSize: "20px" }} />;
    }
  };

  const loadVCS = () => {
    axiosInstance.get(`organization/${organizationId}/vcs`).then((response) => {
      setVCS(response.data.data);
      setLoading(false);
    });
  };

  const [form] = Form.useForm();

  const handleCliDriven = () => {
    setVersionControlFlow(false);
    setCurrent(2);
    setStep4Hidden(false);
    form.setFieldsValue({ source: "empty", branch: "remote-content" });
  };

  const onFinishFailed = () => {
    message.error("Please review the required fields before continuing.");
  };

  const buildWorkspaceMappingRows = (varsets: WorkspaceVarset[]): WorkspaceMappingRow[] => {
    return varsets.map((varset) => ({
      key: varset.id,
      sourceName: varset.name,
      terrakubeCollectionId: undefined,
      isAdditional: false,
    }));
  };

  const buildSensitiveVariableDrafts = (variables: SensitiveVariablePreview[]): SensitiveVariableDraft[] => {
    return variables.map((variable) => ({
      ...variable,
      value: "",
    }));
  };

  const getVariableCategoryLabel = (category?: string) => {
    if (category?.toLowerCase() === "env") {
      return "env";
    }

    return "terraform";
  };

  const getImporterBaseUrl = () => {
    const apiUrl = window._env_?.REACT_APP_TERRAKUBE_API_URL;
    if (apiUrl == null || apiUrl === "") {
      throw new Error("Terrakube API URL is not configured.");
    }

    try {
      return `${new URL(apiUrl).origin}/importer/tfcloud`;
    } catch {
      throw new Error("Terrakube API URL is invalid.");
    }
  };

  const loadAvailableCollections = async (): Promise<CollectionOption[]> => {
    if (organizationId == null || organizationId === "") {
      throw new Error("Organization not found.");
    }

    if (availableCollections.length > 0) {
      return availableCollections;
    }

    const response = await axiosInstance.get(`organization/${organizationId}/collection`);
    const collections = response.data.data.map((collection: CollectionApiRecord) => ({
      id: collection.id,
      name: collection.attributes.name,
    }));
    setAvailableCollections(collections);
    return collections;
  };

  const fetchWorkspaceVarsets = async (
    importerBaseUrl: string,
    workspace: WorkspaceRecord
  ): Promise<WorkspaceMappingRow[]> => {
    const response = await axiosInstance.get(`${importerBaseUrl}/workspaces/${workspace.id}/varsets`, {
      headers: {
        "X-TFC-Token": form.getFieldValue("apiToken"),
        "X-TFC-Url": form.getFieldValue("apiUrl"),
      },
    });

    const varsets: WorkspaceVarset[] = response.data ?? [];
    return buildWorkspaceMappingRows(varsets);
  };

  const fetchWorkspaceSensitiveVariables = async (
    importerBaseUrl: string,
    workspace: WorkspaceRecord
  ): Promise<SensitiveVariableDraft[]> => {
    const response = await axiosInstance.get(`${importerBaseUrl}/workspaces/${workspace.id}/sensitive-variables`, {
      headers: {
        "X-TFC-Token": form.getFieldValue("apiToken"),
        "X-TFC-Url": form.getFieldValue("apiUrl"),
      },
    });

    const sensitiveVariables: SensitiveVariablePreview[] = response.data ?? [];
    return buildSensitiveVariableDrafts(sensitiveVariables);
  };

  const loadWorkspaceImportData = async (importerBaseUrl: string): Promise<WorkspaceMappingLoadResult> => {
    const results = await mapWithConcurrency(
      selectedWorkspaces,
      WORKSPACE_IMPORT_PREVIEW_CONCURRENCY,
      async (workspace) => {
        let mappingRows: WorkspaceMappingRow[] = [];
        let sensitiveVariables: SensitiveVariableDraft[] = [];
        let failedCollections = false;
        let failedSensitiveVariables = false;

        try {
          mappingRows = await fetchWorkspaceVarsets(importerBaseUrl, workspace);
        } catch {
          failedCollections = true;
        }

        try {
          sensitiveVariables = await fetchWorkspaceSensitiveVariables(importerBaseUrl, workspace);
        } catch {
          failedSensitiveVariables = true;
        }

        return {
          workspaceId: workspace.id,
          workspaceName: workspace.attributes.name,
          mappingRows,
          sensitiveVariables,
          failedCollections,
          failedSensitiveVariables,
        };
      }
    );

    const mappings: Record<string, WorkspaceMappingRow[]> = {};
    const sensitiveVariables: Record<string, SensitiveVariableDraft[]> = {};
    const failedCollectionWorkspaceNames: string[] = [];
    const failedSensitiveVariableWorkspaceNames: string[] = [];

    for (const result of results) {
      mappings[result.workspaceId] = result.mappingRows;
      sensitiveVariables[result.workspaceId] = result.sensitiveVariables;
      if (result.failedCollections) {
        failedCollectionWorkspaceNames.push(result.workspaceName);
      }
      if (result.failedSensitiveVariables) {
        failedSensitiveVariableWorkspaceNames.push(result.workspaceName);
      }
    }

    return {
      mappings,
      sensitiveVariables,
      failedCollectionWorkspaceNames,
      failedSensitiveVariableWorkspaceNames,
    };
  };

  const getSelectedCollectionIds = (workspaceId: string): string[] => {
    const mappingRows = workspaceMappings[workspaceId] ?? [];
    const selectedCollectionIds: string[] = [];

    for (const row of mappingRows) {
      if (row.terrakubeCollectionId != null && row.terrakubeCollectionId !== "") {
        selectedCollectionIds.push(row.terrakubeCollectionId);
      }
    }

    return selectedCollectionIds;
  };

  const getSelectedSensitiveVariables = (workspaceId: string) => {
    const selectedSensitiveVariables = workspaceSensitiveVariables[workspaceId] ?? [];
    return selectedSensitiveVariables.map((variable) => ({
      sourceVariableId: variable.id,
      value: variable.value,
    }));
  };

  const beginImport = async () => {
    setStepsHidden(true);
    setListHidden(false);

    const nextImportProgress = selectedWorkspaces.map((workspace) => ({
      id: workspace.id,
      name: workspace.attributes.name,
      status: "Importing...",
    }));
    setImportProgress(nextImportProgress);

    for (const workspace of selectedWorkspaces) {
      const result = await importWorkspace(workspace);

      setImportProgress((prevWorkspaces) =>
        prevWorkspaces.map((item) => {
          if (item.id === workspace.id) {
            return { ...item, status: result };
          }

          return item;
        })
      );
    }
  };

  const handleImportClick = async () => {
    if (selectedWorkspaces.length === 0) {
      message.error("Select at least one workspace to import.");
      return;
    }

    setMappingDataLoading(true);
    try {
      const importerBaseUrl = getImporterBaseUrl();
      await loadAvailableCollections();
      const { mappings, sensitiveVariables, failedCollectionWorkspaceNames, failedSensitiveVariableWorkspaceNames } =
        await loadWorkspaceImportData(importerBaseUrl);

      if (failedSensitiveVariableWorkspaceNames.length > 0) {
        message.error(
          `Failed to load sensitive variables for ${failedSensitiveVariableWorkspaceNames.length} workspace(s). Please try again before importing.`
        );
        return;
      }

      setWorkspaceMappings(mappings);
      setWorkspaceSensitiveVariables(sensitiveVariables);
      setImportProgress([]);
      setCurrentMappingWorkspaceIndex(0);
      setMappingModalOpen(true);
      if (failedCollectionWorkspaceNames.length > 0) {
        message.warning(
          `${failedCollectionWorkspaceNames.length} workspace variable collection list(s) could not be loaded. You can still add Terrakube collections manually.`
        );
      }
    } catch (error) {
      message.error(`Failed to load workspace import details: ${getErrorMessage(error)}`);
    } finally {
      setMappingDataLoading(false);
    }
  };

  const importWorkspace = async (workspace: WorkspaceRecord) => {
    try {
      const importerBaseUrl = getImporterBaseUrl();
      const workingDirectory = workspace.attributes["working-directory"];
      const trimmedWorkingDirectory = workingDirectory?.trim();
      let folder = "/";

      if (trimmedWorkingDirectory != null && trimmedWorkingDirectory !== "") {
        folder = trimmedWorkingDirectory;
      }

      const response = await axiosInstance.post(
        `${importerBaseUrl}/workspaces`,
        {
          organizationId: organizationId,
          vcsId: vcsId,
          id: workspace.id,
          organization: form.getFieldValue("organization"),
          branch: workspace.attributes["vcs-repo"]?.branch,
          folder,
          name: workspace.attributes.name,
          terraformVersion: workspace.attributes["terraform-version"],
          source: workspace.attributes["vcs-repo"]?.["repository-http-url"],
          executionMode: workspace?.attributes["execution-mode"],
          description: workspace.attributes.description,
          variableCollectionIds: getSelectedCollectionIds(workspace.id),
          sensitiveVariables: getSelectedSensitiveVariables(workspace.id),
        },
        {
          headers: {
            "X-TFC-Token": form.getFieldValue("apiToken"),
            "X-TFC-Url": form.getFieldValue("apiUrl"),
          },
        }
      );

      return response?.data;
    } catch (error) {
      return `Failed to import workspace: ${getErrorMessage(error)}`;
    }
  };

  const rowSelection = {
    onChange: (_selectedRowKeys: (string | number | bigint)[], selectedRows: WorkspaceRecord[]) => {
      setSelectedWorkspaces(selectedRows);
    },
    getCheckboxProps: (record: WorkspaceRecord) => ({
      name: record.id,
    }),
  };

  const onFinish = (values: any) => {
    handleChange(4);
    setWorkspacesLoading(true);

    let importerBaseUrl = "";
    try {
      importerBaseUrl = getImporterBaseUrl();
    } catch (error) {
      message.error(getErrorMessage(error));
      setWorkspacesLoading(false);
      return;
    }

    axiosInstance
      .get(`${importerBaseUrl}/workspaces?organization=${values.organization}`, {
        headers: {
          "X-TFC-Token": values.apiToken,
          "X-TFC-Url": values.apiUrl,
        },
      })
      .then((response) => {
        setWorkspaces(response.data);
        setSelectedWorkspaces([]);
        setImportProgress([]);
        setWorkspaceMappings({});
        setWorkspaceSensitiveVariables({});
        setWorkspacesLoading(false);
      })
      .catch((error) => {
        message.error(`Error fetching workspaces: ${getErrorMessage(error)}`);
        setWorkspacesLoading(false);
      });
  };

  const handleChange = (currentVal: number) => {
    setCurrent(currentVal);

    if (currentVal === 3) {
      setStep4Hidden(false);
    } else {
      setStep4Hidden(true);
    }

    if (currentVal === 1 || currentVal === 0) {
      setVersionControlFlow(true);
    }

    if (currentVal === 4) {
      setWorkspacesHidden(false);
    } else {
      setWorkspacesHidden(true);
    }
  };

  const getPlatforms = () => {
    const platforms: Platform[] = [
      {
        id: "tfcloud",
        name: "Terraform Cloud",
        description: "Create an empty template. So you can define your template from scratch.",
        icon: "/platforms/terraform-cloud.svg",
        height: "60px",
      },
      {
        id: "tfenterprise",
        name: "Terraform Enterprise",
        icon: "/platforms/terraform-enterprise.svg",
        height: "50px",
      },
    ];

    setPlatforms(platforms);
  };

  const currentMappingWorkspace = selectedWorkspaces[currentMappingWorkspaceIndex];
  const currentMappingRows =
    currentMappingWorkspace == null ? [] : (workspaceMappings[currentMappingWorkspace.id] ?? []);
  const currentSensitiveVariableRows =
    currentMappingWorkspace == null ? [] : (workspaceSensitiveVariables[currentMappingWorkspace.id] ?? []);
  const totalMappingWorkspaces = selectedWorkspaces.length;
  const currentMappingStep = Math.min(currentMappingWorkspaceIndex + 1, totalMappingWorkspaces);
  const remainingWorkspaceCount = Math.max(totalMappingWorkspaces - currentMappingStep, 0);
  const mappingProgressPercent =
    totalMappingWorkspaces === 0 ? 0 : Math.round((currentMappingStep / totalMappingWorkspaces) * 100);
  const selectedCollectionCount =
    currentMappingWorkspace == null ? 0 : getSelectedCollectionIds(currentMappingWorkspace.id).length;
  const selectedSensitiveVariableCount = currentSensitiveVariableRows.length;
  const incompleteSensitiveVariableCount = currentSensitiveVariableRows.filter((variable) => {
    return variable.value.trim() === "";
  }).length;
  const collectionOptions = [
    { value: NONE_COLLECTION_VALUE, label: "None" },
    ...availableCollections.map((collection) => ({
      value: collection.id,
      label: collection.name,
    })),
  ];

  const updateCurrentWorkspaceMappings = (nextRows: WorkspaceMappingRow[]) => {
    if (currentMappingWorkspace == null) {
      return;
    }

    setWorkspaceMappings((prevMappings) => ({
      ...prevMappings,
      [currentMappingWorkspace.id]: nextRows,
    }));
  };

  const updateCurrentWorkspaceSensitiveVariables = (nextRows: SensitiveVariableDraft[]) => {
    if (currentMappingWorkspace == null) {
      return;
    }

    setWorkspaceSensitiveVariables((prevSensitiveVariables) => ({
      ...prevSensitiveVariables,
      [currentMappingWorkspace.id]: nextRows,
    }));
  };

  const handleMappingSelectionChange = (rowKey: string, value: string) => {
    const nextRows = currentMappingRows.map((row) => {
      if (row.key !== rowKey) {
        return row;
      }

      if (value === NONE_COLLECTION_VALUE) {
        return { ...row, terrakubeCollectionId: undefined };
      }

      return { ...row, terrakubeCollectionId: value };
    });

    updateCurrentWorkspaceMappings(nextRows);
  };

  const handleAddAdditionalCollection = () => {
    const nextRows = [
      ...currentMappingRows,
      {
        key: `additional-${Date.now()}`,
        sourceName: "Additional variable collection",
        terrakubeCollectionId: undefined,
        isAdditional: true,
      },
    ];

    updateCurrentWorkspaceMappings(nextRows);
  };

  const handleRemoveAdditionalCollection = (rowKey: string) => {
    const nextRows = currentMappingRows.filter((row) => row.key !== rowKey);
    updateCurrentWorkspaceMappings(nextRows);
  };

  const handleSensitiveVariableValueChange = (variableId: string, value: string) => {
    const nextRows = currentSensitiveVariableRows.map((variable) => {
      if (variable.id !== variableId) {
        return variable;
      }

      return {
        ...variable,
        value,
      };
    });

    updateCurrentWorkspaceSensitiveVariables(nextRows);
  };

  const handleRemoveSensitiveVariable = (variableId: string) => {
    const nextRows = currentSensitiveVariableRows.filter((variable) => variable.id !== variableId);
    updateCurrentWorkspaceSensitiveVariables(nextRows);
  };

  const handleCloseMappingModal = () => {
    if (mappingTransitionLoading) {
      return;
    }

    setMappingModalOpen(false);
    setCurrentMappingWorkspaceIndex(0);
  };

  const handleNextWorkspaceMapping = async () => {
    if (mappingTransitionLoading) {
      return;
    }

    if (currentMappingWorkspace == null) {
      handleCloseMappingModal();
      return;
    }

    const isLastWorkspace = currentMappingWorkspaceIndex === selectedWorkspaces.length - 1;
    if (!isLastWorkspace) {
      setCurrentMappingWorkspaceIndex((prevIndex) => prevIndex + 1);
      return;
    }

    setMappingTransitionLoading(true);
    setMappingModalOpen(false);
    setCurrentMappingWorkspaceIndex(0);
    try {
      await beginImport();
    } finally {
      setMappingTransitionLoading(false);
    }
  };

  return (
    <Content style={{ padding: "0 50px" }}>
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: organizationName,
          },
          {
            title: <Link to={`/organizations/${organizationId}/workspaces`}>Workspaces</Link>,
          },
          {
            title: "Import Workspaces",
          },
        ]}
      />

      <div className="site-layout-content" style={{ background: colorBgContainer }}>
        <div className="importWorkspace">
          <h2>Import Workspaces</h2>
          <div className="App-text">
            Easily transfer workspaces from Terraform Cloud and Terraform Enterprise to Terrakube.
            <Alert
              title="Warning"
              description="Only approved URLs can be used when using Terraform Enterprise to import workspaces. Please verify with your Terrakube administrator if you encounter any issues."
              type="warning"
              showIcon
              style={{ marginBottom: "20px" }}
            />
          </div>
          <Content hidden={stepsHidden}>
            <Steps direction="horizontal" size="small" current={current} onChange={handleChange} items={stepItems} />
            {current == 0 && (
              <Space className="chooseType" direction="vertical">
                <h3>Select a Platform for Workspace Import </h3>
                <List
                  grid={{ gutter: 1, column: 4 }}
                  dataSource={platforms}
                  renderItem={(item) => (
                    <List.Item>
                      <Card
                        style={{
                          width: "240px",
                          height: "120px",
                          textAlign: "center",
                        }}
                        hoverable
                        onClick={() => handlePlatformClick(item)}
                      >
                        <Space direction="vertical">
                          <img
                            style={{
                              padding: "6px",
                              height: item.height,
                            }}
                            alt="example"
                            src={item.icon}
                          />
                        </Space>
                      </Card>
                    </List.Item>
                  )}
                />
              </Space>
            )}

            {current === 1 && (
              <Space className="chooseType" direction="vertical">
                <h3>Choose your workflow </h3>
                <Card hoverable onClick={handleClick}>
                  <IconContext.Provider value={{ size: "1.3em" }}>
                    <BiBookBookmark />
                  </IconContext.Provider>
                  <span className="workflowType">Version control workflow</span>
                  <div className="workflowDescription App-text">
                    Store your Terraform configuration in a git repository, and trigger runs based on pull requests and
                    merges.
                  </div>
                  <div className="workflowSelect"></div>
                </Card>
                <Card hoverable onClick={handleCliDriven}>
                  <IconContext.Provider value={{ size: "1.3em" }}>
                    <BiTerminal />
                  </IconContext.Provider>
                  <span className="workflowType">CLI-driven workflow</span>
                  <div className="workflowDescription App-text">
                    Trigger remote Terraform runs from your local command line.
                  </div>
                </Card>
                <Card hoverable onClick={handleCliDriven}>
                  <IconContext.Provider value={{ size: "1.3em" }}>
                    <BiUpload />
                  </IconContext.Provider>
                  <span className="workflowType">API-driven workflow</span>
                  <div className="workflowDescription App-text">
                    A more advanced option. Integrate Terraform into a larger pipeline using the Terraform API.
                  </div>
                </Card>
              </Space>
            )}

            {current === 2 && versionControlFlow && (
              <Space className="chooseType" direction="vertical">
                <h3>Connect to a version control provider</h3>
                <div className="workflowDescription2 App-text">
                  Choose the version control provider hosting your Terraform configurations for workspace import. For
                  workspaces across different VCS providers, please run the importer separately for each.
                </div>

                {vcsButtonsVisible ? (
                  <div>
                    <Space direction="horizontal">
                      {loading || vcs.length === 0 ? (
                        <p>Data loading...</p>
                      ) : (
                        vcs.map(function (item) {
                          return (
                            <Button
                              key={item.id}
                              icon={renderVCSLogo(item.attributes.vcsType)}
                              onClick={() => {
                                handleGitClick(item.id);
                              }}
                              size="large"
                            >
                              &nbsp;{item.attributes.name}
                            </Button>
                          );
                        })
                      )}
                    </Space>{" "}
                    <br />
                    <Button onClick={handleConnectDifferent} className="link" type="link">
                      Connect to a different VCS
                    </Button>
                  </div>
                ) : (
                  <div>
                    <Space direction="horizontal">
                      <Dropdown menu={{ items: githubItems }}>
                        <Button size="large">
                          <Space>
                            <GithubOutlined /> Github <DownOutlined />
                          </Space>
                        </Button>
                      </Dropdown>
                      <Dropdown menu={{ items: gitlabItems }}>
                        <Button size="large">
                          <Space>
                            <GitlabOutlined />
                            Gitlab <DownOutlined />
                          </Space>
                        </Button>
                      </Dropdown>
                      <Dropdown menu={{ items: bitBucketItems }}>
                        <Button size="large">
                          <SiBitbucket /> &nbsp; Bitbucket <DownOutlined />
                        </Button>
                      </Dropdown>
                      <Dropdown menu={{ items: azDevOpsItems }}>
                        <Button size="large">
                          <Space>
                            <VscAzureDevops /> Azure Devops <DownOutlined />
                          </Space>
                        </Button>
                      </Dropdown>
                    </Space>
                    <br />
                    <Button onClick={handleConnectExisting} className="link" type="link">
                      Use an existing VCS connection
                    </Button>
                  </div>
                )}
              </Space>
            )}

            <Form
              form={form}
              name="create-workspace"
              layout="vertical"
              onFinish={onFinish}
              onFinishFailed={onFinishFailed}
              validateMessages={validateMessages}
              initialValues={{
                apiUrl: platform.id === "tfcloud" ? "https://app.terraform.io/api/v2" : "",
              }}
            >
              <Space hidden={step3Hidden} className="chooseType" direction="vertical">
                <h3>Connect to Platform</h3>
                <div className="workflowDescription2 App-text">
                  Provide the API token to connect with Terraform Cloud API. Terrakube will use this token exclusively
                  for the duration of the migration process and will not store it. For guidance on generating an API
                  token, refer to the{" "}
                  <a href="https://developer.hashicorp.com/terraform/cloud-docs/users-teams-organizations/api-tokens">
                    Terraform Cloud documentation on API tokens
                  </a>
                  .
                </div>
                <Form.Item name="apiUrl" label="API URL" hidden={apiUrlHidden} rules={[{ required: true }]}>
                  <Input placeholder="ex. https://<TERRAFORM ENTERPRISE HOSTNAME>/api/v2" />
                </Form.Item>
                <Form.Item
                  name="organization"
                  label="Organization"
                  extra="Organization name where the workspaces are located."
                  rules={[{ required: true }]}
                >
                  <Input placeholder="ex. My-Organization" />
                </Form.Item>
                <Form.Item
                  name="apiToken"
                  label="Api Token"
                  rules={[{ required: true }]}
                  extra="Ensure that the provided API token has permissions to access the workspaces you intend to import."
                >
                  <Input.Password />
                </Form.Item>
                <Form.Item>
                  <Button type="primary" htmlType="submit">
                    Continue
                  </Button>
                </Form.Item>
              </Space>
            </Form>

            <Space className="chooseType" hidden={workspacesHidden} direction="vertical">
              <h3>Import Workspaces</h3>
              <div className="workflowDescription2 App-text">
                Select one or multiple workspaces that you wish to import. After making your selection, click the
                &apos;Import&apos; button to initiate the import process. The chosen workspaces will be imported into
                the organization specified in the previous step.
              </div>
              <Spin spinning={workspacesLoading} tip="Loading Workspaces...">
                <Table
                  rowSelection={{
                    type: "checkbox",
                    ...rowSelection,
                  }}
                  rowKey={(record: WorkspaceRecord) => record.id}
                  dataSource={workspaces}
                  columns={columns}
                  pagination={{
                    defaultPageSize: 50,
                    showSizeChanger: true,
                    pageSizeOptions: ["20", "50", "100"],
                  }}
                />
                <br />

                <Button onClick={handleImportClick} type="primary" htmlType="button" loading={mappingDataLoading}>
                  Import Workspaces
                </Button>
              </Spin>
            </Space>
          </Content>
          <Modal
            title={
              currentMappingWorkspace == null
                ? "Map variable collections"
                : `Map variable collections for ${currentMappingWorkspace.attributes.name}`
            }
            open={mappingModalOpen}
            onCancel={handleCloseMappingModal}
            maskClosable={false}
            width={900}
            footer={[
              <Button key="cancel" onClick={handleCloseMappingModal} disabled={mappingTransitionLoading}>
                Cancel
              </Button>,
              <Button
                key="next"
                type="primary"
                onClick={handleNextWorkspaceMapping}
                loading={mappingTransitionLoading}
                disabled={mappingTransitionLoading}
              >
                {currentMappingWorkspaceIndex === selectedWorkspaces.length - 1 ? "Start Import" : "Next Workspace"}
              </Button>,
            ]}
          >
            <Space direction="vertical" style={{ width: "100%" }} size="large">
              <Card
                size="small"
                style={{
                  borderRadius: "12px",
                  background: "linear-gradient(135deg, rgba(22,119,255,0.08) 0%, rgba(22,119,255,0.02) 100%)",
                }}
              >
                <Space direction="vertical" style={{ width: "100%" }} size="middle">
                  <div
                    style={{
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                      gap: "16px",
                      flexWrap: "wrap",
                    }}
                  >
                    <div>
                      <Typography.Text strong>Variable collection mapping progress</Typography.Text>
                      <div>
                        <Typography.Text type="secondary">
                          Workspace {currentMappingStep} of {totalMappingWorkspaces}
                        </Typography.Text>
                      </div>
                    </div>
                    <Typography.Text strong>{mappingProgressPercent}%</Typography.Text>
                  </div>
                  <Progress percent={mappingProgressPercent} showInfo={false} strokeLinecap="round" />
                  <div
                    style={{
                      display: "grid",
                      gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))",
                      gap: "12px",
                    }}
                  >
                    <Card size="small" style={{ borderRadius: "10px" }}>
                      <Typography.Text type="secondary">Current workspace</Typography.Text>
                      <div>
                        <Typography.Text strong>
                          {currentMappingWorkspace == null ? "Not selected" : currentMappingWorkspace.attributes.name}
                        </Typography.Text>
                      </div>
                    </Card>
                    <Card size="small" style={{ borderRadius: "10px" }}>
                      <Typography.Text type="secondary">Collections selected</Typography.Text>
                      <div>
                        <Typography.Text strong>{selectedCollectionCount}</Typography.Text>
                      </div>
                    </Card>
                    <Card size="small" style={{ borderRadius: "10px" }}>
                      <Typography.Text type="secondary">Sensitive variables</Typography.Text>
                      <div>
                        <Typography.Text strong>{selectedSensitiveVariableCount}</Typography.Text>
                      </div>
                    </Card>
                    <Card size="small" style={{ borderRadius: "10px" }}>
                      <Typography.Text type="secondary">Still incomplete</Typography.Text>
                      <div>
                        <Typography.Text strong>{incompleteSensitiveVariableCount}</Typography.Text>
                      </div>
                    </Card>
                    <Card size="small" style={{ borderRadius: "10px" }}>
                      <Typography.Text type="secondary">Workspaces remaining</Typography.Text>
                      <div>
                        <Typography.Text strong>{remainingWorkspaceCount}</Typography.Text>
                      </div>
                    </Card>
                  </div>
                </Space>
              </Card>
              <Card
                size="small"
                title="Review import details for this workspace"
                style={{ borderRadius: "12px" }}
                bodyStyle={{ paddingTop: "12px" }}
              >
                <Typography.Text type="secondary">
                  Match each Terraform Cloud variable collection to an existing Terrakube variable collection. You can
                  also attach extra Terrakube variable collections that were not present on the source workspace. Any
                  sensitive variables shown below can be filled now, left blank to stay incomplete, or discarded.
                </Typography.Text>
              </Card>
              {availableCollections.length === 0 && (
                <Alert
                  type="info"
                  showIcon
                  message="No variable collections are available in this Terrakube organization yet. You can still continue without attaching any."
                />
              )}
              {currentMappingRows.length === 0 && (
                <Alert
                  type="info"
                  showIcon
                  message="This workspace does not currently have any Terraform Cloud variable collections. You can add Terrakube variable collections below if needed."
                />
              )}
              {currentMappingRows.map((row) => (
                <Card
                  key={row.key}
                  size="small"
                  style={{
                    borderRadius: "12px",
                    borderColor: row.isAdditional ? "rgba(22,119,255,0.25)" : undefined,
                    background: row.isAdditional ? "rgba(22,119,255,0.03)" : undefined,
                  }}
                >
                  <div
                    style={{
                      display: "grid",
                      gridTemplateColumns: row.isAdditional ? "1fr 1fr auto" : "1fr 1fr",
                      gap: "16px",
                      alignItems: "end",
                    }}
                  >
                    <div>
                      <Typography.Text type="secondary">
                        {row.isAdditional ? "Additional Terrakube collection" : "Terraform Cloud collection"}
                      </Typography.Text>
                      <div style={{ marginTop: "6px" }}>
                        <Typography.Text strong>{row.sourceName}</Typography.Text>
                      </div>
                    </div>
                    <div>
                      <Typography.Text type="secondary">Terrakube replacement</Typography.Text>
                      <div style={{ marginTop: "6px" }}>
                        <Select
                          value={row.terrakubeCollectionId ?? NONE_COLLECTION_VALUE}
                          onChange={(value) => handleMappingSelectionChange(row.key, value)}
                          options={collectionOptions}
                          showSearch
                          optionFilterProp="label"
                          style={{ width: "100%" }}
                          placeholder="Select a Terrakube variable collection"
                        />
                      </div>
                    </div>
                    {row.isAdditional && (
                      <Button
                        danger
                        type="text"
                        icon={<DeleteOutlined />}
                        onClick={() => handleRemoveAdditionalCollection(row.key)}
                      >
                        Remove
                      </Button>
                    )}
                  </div>
                </Card>
              ))}
              <Card
                size="small"
                style={{
                  borderRadius: "12px",
                  borderStyle: "dashed",
                }}
              >
                <div
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    gap: "16px",
                    flexWrap: "wrap",
                  }}
                >
                  <div>
                    <Typography.Text strong>Add more coverage</Typography.Text>
                    <div>
                      <Typography.Text type="secondary">
                        Attach any extra Terrakube variable collections that should apply to this imported workspace.
                      </Typography.Text>
                    </div>
                  </div>
                  <Button icon={<PlusOutlined />} onClick={handleAddAdditionalCollection}>
                    Add additional variable collection
                  </Button>
                </div>
              </Card>
              <Card
                size="small"
                title="Sensitive variables that need a value"
                style={{ borderRadius: "12px" }}
                bodyStyle={{ paddingTop: "12px" }}
              >
                <Typography.Text type="secondary">
                  Terraform Cloud does not expose sensitive values during import. Leave a value blank to import that
                  variable as incomplete, which blocks future runs until the value is filled in or the variable is
                  removed.
                </Typography.Text>
              </Card>
              {currentSensitiveVariableRows.length === 0 && (
                <Alert type="info" showIcon message="No sensitive variables need attention for this workspace." />
              )}
              {currentSensitiveVariableRows.map((variable) => (
                <Card
                  key={variable.id}
                  size="small"
                  style={{
                    borderRadius: "12px",
                    borderColor: variable.value.trim() === "" ? "rgba(250, 140, 22, 0.35)" : "rgba(22, 119, 255, 0.2)",
                    background: variable.value.trim() === "" ? "rgba(250, 140, 22, 0.04)" : "rgba(22, 119, 255, 0.03)",
                  }}
                >
                  <div
                    style={{
                      display: "grid",
                      gridTemplateColumns: "minmax(0, 1fr) minmax(280px, 360px) auto",
                      gap: "16px",
                      alignItems: "end",
                    }}
                  >
                    <div>
                      <Typography.Text type="secondary">Sensitive variable</Typography.Text>
                      <div
                        style={{
                          marginTop: "6px",
                          display: "flex",
                          alignItems: "center",
                          gap: "8px",
                          flexWrap: "wrap",
                        }}
                      >
                        <Typography.Text strong>{variable.key}</Typography.Text>
                        <Typography.Text type="secondary">
                          {getVariableCategoryLabel(variable.category)}
                        </Typography.Text>
                        {variable.hcl && <Typography.Text type="secondary">HCL</Typography.Text>}
                      </div>
                      {variable.description != null && variable.description !== "" && (
                        <div style={{ marginTop: "8px" }}>
                          <Typography.Text type="secondary">{variable.description}</Typography.Text>
                        </div>
                      )}
                      <div style={{ marginTop: "8px" }}>
                        <Typography.Text type={variable.value.trim() === "" ? "warning" : "secondary"}>
                          {variable.value.trim() === ""
                            ? "Will be imported as incomplete until a value is added later."
                            : "A replacement value will be stored during import."}
                        </Typography.Text>
                      </div>
                    </div>
                    <div>
                      <Typography.Text type="secondary">Replacement value</Typography.Text>
                      <div style={{ marginTop: "6px" }}>
                        <Input.Password
                          value={variable.value}
                          onChange={(event) => handleSensitiveVariableValueChange(variable.id, event.target.value)}
                          placeholder="Optional. Leave empty to keep this variable incomplete."
                        />
                      </div>
                    </div>
                    <Button
                      danger
                      type="text"
                      icon={<DeleteOutlined />}
                      onClick={() => handleRemoveSensitiveVariable(variable.id)}
                    >
                      Discard
                    </Button>
                  </div>
                </Card>
              ))}
            </Space>
          </Modal>
          <Space hidden={listHidden} direction="vertical">
            <h3>Importing Workspaces</h3>
            <div className="workflowDescription2 App-text">
              Import of the selected workspaces is underway. You can monitor the progress for each workspace in the
              section below.
            </div>
            <List
              dataSource={importProgress}
              renderItem={(item) => (
                <List.Item>
                  {" "}
                  <List.Item.Meta title={item.name} description={<ul>{parse(item.status || "Waiting...")}</ul>} />
                </List.Item>
              )}
            ></List>
          </Space>
        </div>
      </div>
    </Content>
  );
};
