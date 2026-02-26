import { AxiosInstance } from "axios";
import { DateTime } from "luxon";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import { FlatJob, FlatJobHistory, FlatVariable, Schedule, Template, VcsType, StateOutputValue } from "../types";
import { getIaCNameById } from "./Workspaces";

export type StateOutputVariableWithName = { name: string } & StateOutputValue;

export const include = {
  VARIABLE: "variable",
  JOB: "job",
  HISTORY: "history",
  SCHEDULE: "schedule",
  VCS: "vcs",
  AGENT: "agent",
  WEBHOOK: "webhook",
  REFERENCE: "reference",
  ORGANIZATION: "organization",
};

export async function setupWorkspaceIncludes(
  data: any,
  setVariables: (val: any[]) => void,
  setJobs: (val: any[]) => void,
  setEnvVariables: (val: any[]) => void,
  setHistory: (val: FlatJobHistory[]) => void,
  setSchedule: (val: any[]) => void,
  templates: Template[],
  setLastRun: (val: string) => void,
  setVCSProvider: (val: VcsType) => void,
  setCurrentStateId: (val: string) => void,
  currentStateId: string,
  axiosInstance: any,
  setResources: (val: any[]) => void,
  setOutputs: (val: any[]) => void,
  setAgent: (val: any) => void,
  _loadWebhook: boolean,
  setContextState: (val: any) => void,
  setCollectionVariables: (val: any[]) => void,
  setCollectionEnvVariables: (val: any[]) => void,
  setGlobalVariables: (val: FlatVariable[]) => void,
  setGlobalEnvVariables: (val: FlatVariable[]) => void
): Promise<void> {
  const variables: FlatVariable[] = [];
  const jobs: FlatJob[] = [];
  const webhooks: any = {};
  const envVariables: FlatVariable[] = [];
  const history: FlatJobHistory[] = [];
  const schedule: Schedule[] = [];
  const collectionVariables: any[] = [];
  const collectionEnvVariables: any[] = [];
  const globalVariables: FlatVariable[] = [];
  const globalEnvVariables: FlatVariable[] = [];
  const includes = data.included;
  const asyncPromises: Promise<void>[] = [];

  includes.forEach((element: any) => {
    switch (element.type) {
      case include.ORGANIZATION:
        asyncPromises.push(
          axiosInstance.get(`/organization/${element.id}/globalvar`).then((response: any) => {
            const globalVar = response.data.data;
            if (globalVar != null) {
              globalVar.forEach((variableItem: any) => {
                if (variableItem.attributes.category === "ENV") {
                  globalEnvVariables.push({
                    id: variableItem.id,
                    type: variableItem.type,
                    ...variableItem.attributes,
                  });
                } else {
                  globalVariables.push({
                    id: variableItem.id,
                    type: variableItem.type,
                    ...variableItem.attributes,
                  });
                }
              });
            }
          })
        );
        break;
      case include.JOB:
        let finalColor = "";
        switch (element.attributes.status) {
          case "completed":
            finalColor = "#2eb039";
            break;
          case "noChanges":
            finalColor = "#9f37fa";
            break;
          case "rejected":
            finalColor = "#FB0136";
            break;
          case "failed":
            finalColor = "#FB0136";
            break;
          case "running":
            finalColor = "#108ee9";
            break;
          case "waitingApproval":
            finalColor = "#fa8f37";
            break;
          default:
            finalColor = "";
            break;
        }
        jobs.push({
          id: element.id,
          title: "Queue manually using " + getIaCNameById(data?.data?.attributes?.iacType),
          statusColor: finalColor,
          commitId: element.attributes.commitId,
          stepNumber: element.attributes.stepNumber,
          latestChange: DateTime.fromISO(element.attributes.createdDate).toRelative(),
          ...element.attributes,
        });
        setLastRun(element.attributes.updatedDate);
        break;
      case include.HISTORY:
        console.log(element);
        history.push({
          id: element.id,
          title: "Queue manually using " + getIaCNameById(data?.data?.attributes?.iacType),
          relativeDate: DateTime.fromISO(element.attributes.createdDate).toRelative(),
          createdDate: element.attributes.createdDate,
          ...element.attributes,
        });
        break;

      case include.SCHEDULE:
        schedule.push({
          id: element.id,
          name: templates?.find((template: Template) => template.id === element.attributes.templateReference)
            ?.attributes?.name,
          ...element.attributes,
        });
        break;
      case include.VCS:
        setVCSProvider(element.attributes.vcsType);
        break;
      case include.AGENT:
        setAgent(element.id);
        break;
      case include.VARIABLE:
        if (element.attributes.category == "ENV") {
          envVariables.push({
            id: element.id,
            type: element.type,
            ...element.attributes,
          });
        } else {
          variables.push({
            id: element.id,
            type: element.type,
            ...element.attributes,
          });
        }
        break;
      case include.WEBHOOK:
        webhooks[element.attributes.event] = {
          id: element.id,
          type: element.type,
          ...element.attributes,
        };
        break;
      case include.REFERENCE:
        asyncPromises.push(
          axiosInstance.get(`/reference/${element.id}/collection?include=item`).then((response: any) => {
            const collectionInfo = response.data.data;
            if (response.data.included != null) {
              const items = response.data.included;
              items.forEach((item: any) => {
                item.attributes.priority = collectionInfo.attributes.priority;
                item.attributes.collectionName = collectionInfo.attributes.name;
                if (item.attributes.category === "ENV") {
                  collectionEnvVariables.push({
                    id: item.id,
                    type: item.type,
                    ...item.attributes,
                  });
                } else {
                  collectionVariables.push({
                    id: item.id,
                    type: item.type,
                    ...item.attributes,
                  });
                }
              });
            }
          })
        );
        break;
    }
  });

  await Promise.all(asyncPromises).catch((err) => {
    console.error("Error loading workspace includes:", err);
  });

  const byKey = (a: { key?: string }, b: { key?: string }) =>
    (a.key ?? "").localeCompare(b.key ?? "", undefined, { sensitivity: "base" });

  setVariables([...variables].sort(byKey));
  setEnvVariables([...envVariables].sort(byKey));
  setJobs(jobs);
  setHistory(history);
  setSchedule(schedule);
  setCollectionVariables([...collectionVariables].sort(byKey));
  setCollectionEnvVariables([...collectionEnvVariables].sort(byKey));
  setGlobalVariables([...globalVariables].sort(byKey));
  setGlobalEnvVariables([...globalEnvVariables].sort(byKey));

  // set state data
  const lastState = history
    .sort((a: FlatJobHistory, b: FlatJobHistory) => parseInt(a.jobReference) - parseInt(b.jobReference))
    .reverse()[0];
  // reload state only if there is a new version

  if (currentStateId !== lastState?.id) {
    const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
    const workspaceId = sessionStorage.getItem(WORKSPACE_ARCHIVE);
    const url = `${
      new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
    }/access-token/v1/teams/permissions/organization/${organizationId}/workspace/${workspaceId}`;
    axiosInstance
      .get(url)
      .then((response: any) => {
        loadState(
          lastState,
          axiosInstance,
          setOutputs,
          setResources,
          sessionStorage.getItem(WORKSPACE_ARCHIVE)!,
          setContextState,
          response.data.manageState
        );
      })
      .catch((err: any) => {
        console.error("Failed to load workspace permissions for state:", err);
      });
  }
  setCurrentStateId(lastState?.id);
}

export function loadState(
  state: any,
  axiosInstance: AxiosInstance,
  setOutputs: (val: any) => void,
  setResources: (val: any) => void,
  workspaceId: string,
  setContextState: (val: any) => void,
  manageState: boolean
) {
  if (!state || !manageState) {
    return;
  }

  let currentState;
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);

  axiosInstance
    .get(state.output)
    .then((resp) => {
      let result = parseState(resp.data);
      setContextState(resp.data);
      if (result.outputs.length < 1 && result.resources.length < 1) {
        axiosInstance
          .get(
            `${
              new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
            }/tfstate/v1/organization/${organizationId}/workspace/${workspaceId}/state/terraform.tfstate`
          )
          .then((currentStateData) => {
            currentState = currentStateData.data;
            setContextState(currentState);
            result = parseOldState(currentState);

            setResources(result.resources);
            setOutputs(result.outputs);
          })
          .catch(function (error: Error) {
            console.error(error);
          });
      } else {
        setResources(result.resources);
        setOutputs(result.outputs);
      }
    })
    .catch((err) => {
      console.error("Failed to load state data:", err);
    });
}

export function parseState(state: any) {
  let resources: any[] = [];
  const outputs: StateOutputVariableWithName[] = [];

  // parse root outputs
  if (state?.values?.outputs != null) {
    for (const [key, value] of Object.entries(state?.values?.outputs) as [any, any][]) {
      if (typeof value.type === "string") {
        if (value.sensitive) {
          outputs.push({
            name: key,
            type: value.type,
            value: "*****",
          });
        } else {
          outputs.push({
            name: key,
            type: value.type,
            value: value.value,
          });
        }
      } else {
        const jsonObject = JSON.stringify(value.value);
        if (value.sensitive) {
          outputs.push({
            name: key,
            type: "Other type",
            value: "*****",
          });
        } else {
          outputs.push({
            name: key,
            type: "Other type",
            value: jsonObject,
          });
        }
      }
    }
  } else {
    console.log("State has no outputs");
  }

  // parse root module resources
  if (state?.values?.root_module?.resources != null) {
    for (const [_, value] of Object.entries(state?.values?.root_module?.resources) as [any, any][]) {
      resources.push({
        name: value.name,
        type: value.type,
        provider: value.provider_name,
        module: "root_module",
        values: value.values,
        depends_on: value.depends_on,
      });
    }
  } else {
    console.log("State has no resources");
  }

  // parse child module resources
  if (state?.values?.root_module?.child_modules?.length > 0) {
    state?.values?.root_module?.child_modules?.forEach((moduleVal: any, index: any) => {
      if (moduleVal.resources != null)
        for (const [_, value] of Object.entries(moduleVal.resources) as [any, any][]) {
          resources.push({
            name: value.name,
            type: value.type,
            provider: value.provider_name,
            module: moduleVal.address,
            values: value.values,
            depends_on: value.depends_on,
          });
        }

      if (moduleVal.child_modules?.length > 0) {
        resources = parseChildModules(resources, moduleVal.child_modules);
      }
    });
  } else {
    console.log("State has no child modules resources");
  }

  return { resources: resources, outputs: outputs };
}

export function parseOldState(state: any) {
  const resources: any[] = [];
  const outputs = [];
  if (state?.outputs != null) {
    for (const [key, value] of Object.entries(state?.outputs) as [any, any][]) {
      if (typeof value.type === "string") {
        if (value.sensitive) {
          outputs.push({
            name: key,
            type: value.type,
            value: "********",
          });
        } else {
          outputs.push({
            name: key,
            type: value.type,
            value: value.value,
          });
        }
      } else {
        const jsonObject = JSON.stringify(value.value);
        if (value.sensitive) {
          outputs.push({
            name: key,
            type: "Other type",
            value: "********",
          });
        } else {
          outputs.push({
            name: key,
            type: "Other type",
            value: jsonObject,
          });
        }
      }
    }
  } else {
    console.log("State has no outputs");
  }

  console.log("Parsing resources and modules fallback method");
  if (state?.resources != null && state?.resources.length > 0) {
    state?.resources.forEach((value: any) => {
      if (value.module != null) {
        resources.push({
          name: value.name,
          type: value.type,
          provider: value.provider.replace("provider[", "").replace("]", ""),
          module: value.module,
          values: value.instances[0].attributes,
          depends_on: value.instances[0].dependencies,
        });
      } else {
        resources.push({
          name: value.name,
          type: value.type,
          provider: value.provider.replace('provider["', "").replace('"]', ""),
          module: "root_module",
          values: value.instances[0].attributes,
          depends_on: value.instances[0].dependencies,
        });
      }
    });
  } else {
    console.log("State has no resources/modules");
  }

  return { resources: resources, outputs: outputs };
}

export function parseChildModules(resources: any, child_modules?: any) {
  child_modules?.forEach((moduleVal: any, index: number) => {
    if (moduleVal.resources != null)
      for (const [_, value] of Object.entries(moduleVal.resources) as [any, any][]) {
        resources.push({
          name: value.name,
          type: value.type,
          provider: value.provider_name,
          module: moduleVal.address,
          values: value.values,
          depends_on: value.depends_on,
        });
      }

    if (moduleVal.child_modules?.length > 0) {
      resources = parseChildModules(resources);
    }
  });

  return resources;
}

export function isValidUrl(source: string): boolean {
  try {
    new URL(source);
    return true;
  } catch {
    return false;
  }
}

export function fixSshURL(source: string) {
  if (source.startsWith("git@")) {
    return source.replace(":", "/").replace("git@", "https://");
  } else {
    return source;
  }
}
