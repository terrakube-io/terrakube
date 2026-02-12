import axiosInstance, { axiosRegistry } from "../../config/axiosConfig";
import {
  ProviderModel,
  ProviderVersionModel,
  ProviderImplementationModel,
  TerraformRegistrySearchResult,
  TerraformRegistryProviderVersions,
  TerraformRegistryProviderDownload,
} from "./types";

const JSON_API_CONTENT_TYPE = "application/vnd.api+json";

// Terrakube API functions

export const listProviders = async (
  orgId: string
): Promise<{ data: ProviderModel[] }> => {
  const response = await axiosInstance.get(
    `organization/${orgId}/provider`
  );
  return response.data;
};

export const getProvider = async (
  orgId: string,
  providerId: string
): Promise<{
  data: ProviderModel;
  included?: (ProviderVersionModel | ProviderImplementationModel)[];
}> => {
  const response = await axiosInstance.get(
    `organization/${orgId}/provider/${providerId}?include=version`
  );
  return response.data;
};

export const createProvider = async (
  orgId: string,
  name: string,
  description: string
): Promise<{ data: ProviderModel }> => {
  const response = await axiosInstance.post(
    `organization/${orgId}/provider`,
    {
      data: {
        type: "provider",
        attributes: {
          name,
          description,
        },
      },
    },
    {
      headers: {
        "Content-Type": JSON_API_CONTENT_TYPE,
      },
    }
  );
  return response.data;
};

export const createVersion = async (
  orgId: string,
  providerId: string,
  versionNumber: string,
  protocols: string
): Promise<{ data: ProviderVersionModel }> => {
  const response = await axiosInstance.post(
    `organization/${orgId}/provider/${providerId}/version`,
    {
      data: {
        type: "version",
        attributes: {
          versionNumber,
          protocols,
        },
      },
    },
    {
      headers: {
        "Content-Type": JSON_API_CONTENT_TYPE,
      },
    }
  );
  return response.data;
};

export const createImplementation = async (
  orgId: string,
  providerId: string,
  versionId: string,
  impl: {
    os: string;
    arch: string;
    filename: string;
    downloadUrl: string;
    shasumsUrl: string;
    shasumsSignatureUrl: string;
    shasum: string;
    keyId: string;
    asciiArmor: string;
    trustSignature: string;
    source: string;
    sourceUrl: string;
  }
): Promise<{ data: ProviderImplementationModel }> => {
  const response = await axiosInstance.post(
    `organization/${orgId}/provider/${providerId}/version/${versionId}/implementation`,
    {
      data: {
        type: "implementation",
        attributes: impl,
      },
    },
    {
      headers: {
        "Content-Type": JSON_API_CONTENT_TYPE,
      },
    }
  );
  return response.data;
};

/**
 * Delete a provider and all its children (implementations, then versions).
 * The backend has no cascade delete, so we must delete children first.
 */
export const deleteProviderCascade = async (
  orgId: string,
  providerId: string
): Promise<void> => {
  // 1. Get provider with versions and implementations
  const response = await axiosInstance.get(
    `organization/${orgId}/provider/${providerId}?include=version,version.implementation`
  );

  const included = response.data.included || [];

  // Collect implementation IDs and version IDs
  const implementations: { id: string; versionId: string }[] = [];
  const versionIds: string[] = [];

  for (const item of included) {
    if (item.type === "version") {
      versionIds.push(item.id);
    }
    if (item.type === "implementation") {
      implementations.push({
        id: item.id,
        versionId: item.relationships?.version?.data?.id || "",
      });
    }
  }

  // 2. Delete all implementations first
  for (const impl of implementations) {
    if (impl.versionId) {
      await axiosInstance.delete(
        `organization/${orgId}/provider/${providerId}/version/${impl.versionId}/implementation/${impl.id}`
      );
    }
  }

  // 3. Delete all versions
  for (const versionId of versionIds) {
    await axiosInstance.delete(
      `organization/${orgId}/provider/${providerId}/version/${versionId}`
    );
  }

  // 4. Delete the provider itself
  await axiosInstance.delete(
    `organization/${orgId}/provider/${providerId}`
  );
};

// Keep simple delete for backward compatibility
export const deleteProvider = deleteProviderCascade;

// Terraform Registry API functions (via backend proxy to avoid CORS)

export const searchTerraformRegistry = async (
  query: string
): Promise<TerraformRegistrySearchResult> => {
  const response = await axiosRegistry.get("/registry/v1/providers", {
    params: {
      q: query,
      limit: 20,
    },
  });
  return response.data;
};

export const getProviderVersions = async (
  namespace: string,
  name: string
): Promise<TerraformRegistryProviderVersions> => {
  const response = await axiosRegistry.get(
    `/registry/v1/providers/${namespace}/${name}/versions`
  );
  return response.data;
};

export const getProviderDownload = async (
  namespace: string,
  name: string,
  version: string,
  os: string,
  arch: string
): Promise<TerraformRegistryProviderDownload> => {
  const response = await axiosRegistry.get(
    `/registry/v1/providers/${namespace}/${name}/${version}/download/${os}/${arch}`
  );
  return response.data;
};

// Import provider from Terraform Registry to Terrakube
export const importProvider = async (
  orgId: string,
  namespace: string,
  name: string,
  version: string,
  description?: string,
  prefetchedVersions?: TerraformRegistryProviderVersions
): Promise<{ provider: ProviderModel; version: ProviderVersionModel }> => {
  // 1. Get versions from registry (or use pre-fetched data to avoid duplicate call)
  const versionsData = prefetchedVersions || await getProviderVersions(namespace, name);
  const versionInfo = versionsData.versions.find((v) => v.version === version);

  if (!versionInfo) {
    throw new Error(`Version ${version} not found for provider ${namespace}/${name}`);
  }

  // 2. Create provider in Terrakube
  const desc = description || `${namespace}/${name}`;
  const providerResult = await createProvider(
    orgId,
    `${namespace}/${name}`,
    desc.substring(0, 256)
  );
  const providerId = providerResult.data.id;

  // 3. Create version in Terrakube
  const protocols = versionInfo.protocols?.join(",") || "5.0";
  const versionResult = await createVersion(orgId, providerId, version, protocols);
  const versionId = versionResult.data.id;

  // 4. Get download info for each platform and create implementations (in parallel)
  const platforms = [
    { os: "linux", arch: "amd64" },
    { os: "linux", arch: "arm64" },
    { os: "darwin", arch: "amd64" },
    { os: "darwin", arch: "arm64" },
    { os: "windows", arch: "amd64" },
  ];

  // Filter to only available platforms
  const availablePlatforms = platforms.filter((platform) =>
    versionInfo.platforms?.some(
      (p) => p.os === platform.os && p.arch === platform.arch
    )
  );

  // Fetch all download info in parallel
  const downloadResults = await Promise.allSettled(
    availablePlatforms.map((platform) =>
      getProviderDownload(namespace, name, version, platform.os, platform.arch)
    )
  );

  // Create all implementations in parallel
  const implementationPromises = downloadResults
    .filter(
      (result): result is PromiseFulfilledResult<TerraformRegistryProviderDownload> =>
        result.status === "fulfilled"
    )
    .map((result) => {
      const downloadInfo = result.value;
      const gpgKey = downloadInfo.signing_keys?.gpg_public_keys?.[0];

      // Truncate/default values to fit database column constraints
      const trustSig = gpgKey?.trust_signature || "";
      const keyId = gpgKey?.key_id || "";
      const source = gpgKey?.source || "";
      const sourceUrl = gpgKey?.source_url || "";

      return createImplementation(orgId, providerId, versionId, {
        os: downloadInfo.os?.substring(0, 32) || "",
        arch: downloadInfo.arch?.substring(0, 32) || "",
        filename: downloadInfo.filename?.substring(0, 512) || "",
        downloadUrl: downloadInfo.download_url?.substring(0, 1024) || "",
        shasumsUrl: downloadInfo.shasums_url?.substring(0, 1024) || "",
        shasumsSignatureUrl: downloadInfo.shasums_signature_url?.substring(0, 1024) || "",
        shasum: downloadInfo.shasum?.substring(0, 1024) || "",
        keyId: keyId.substring(0, 32),
        asciiArmor: gpgKey?.ascii_armor || "",
        trustSignature: trustSig.substring(0, 32),
        source: source.substring(0, 64) || "unknown",
        sourceUrl: sourceUrl.substring(0, 512) || "https://unknown",
      }).catch((error) => {
        console.warn(
          `Failed to create implementation for ${downloadInfo.os}/${downloadInfo.arch}:`,
          error
        );
      });
    });

  await Promise.allSettled(implementationPromises);

  return {
    provider: providerResult.data,
    version: versionResult.data,
  };
};


