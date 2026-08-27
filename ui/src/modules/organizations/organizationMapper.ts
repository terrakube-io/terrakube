import { Organization } from "../../domain/types";
import { OrganizationModel } from "./types";

function mapOrganization(apiData: Organization): OrganizationModel {
  return {
    id: apiData.id,
    name: apiData.attributes.name,
    description: apiData.attributes.description,
    executionMode: apiData.attributes.executionMode,
    icon: apiData.attributes.icon,
  };
}

export { mapOrganization };
