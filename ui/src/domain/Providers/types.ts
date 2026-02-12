import { AuditFieldBase } from "@/modules/types";

export type ProviderModel = {
  id: string;
  type: string;
  attributes: ProviderAttributes;
};

export type ProviderAttributes = {
  name: string;
  namespace: string;
  description: string;
  latestVersion?: string;
} & AuditFieldBase;

export type FlatProvider = {
  id: string;
} & ProviderAttributes;

export type ProviderVersionModel = {
  id: string;
  type: string;
  attributes: {
    versionNumber: string;
    protocols: string;
  };
};

export type ProviderImplementationModel = {
  id: string;
  type: string;
  attributes: {
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
  };
};

// Terraform Registry API response types
export type TerraformRegistryProvider = {
  id: string;
  namespace: string;
  name: string;
  alias: string;
  version: string;
  description: string;
  source: string;
  published_at: string;
  downloads: number;
  tier: string;
  logo_url: string;
};

export type TerraformRegistryProviderVersions = {
  versions: {
    version: string;
    protocols: string[];
    platforms: {
      os: string;
      arch: string;
    }[];
  }[];
};

export type TerraformRegistryProviderDownload = {
  protocols: string[];
  os: string;
  arch: string;
  filename: string;
  download_url: string;
  shasums_url: string;
  shasums_signature_url: string;
  shasum: string;
  signing_keys: {
    gpg_public_keys: {
      key_id: string;
      ascii_armor: string;
      trust_signature: string;
      source: string;
      source_url: string;
    }[];
  };
};

export type TerraformRegistrySearchResult = {
  providers: TerraformRegistryProvider[];
};


