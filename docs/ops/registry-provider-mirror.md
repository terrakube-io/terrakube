# Registry provider network mirror

The registry implements the provider network mirror protocol. Terraform and OpenTofu can install any provider through it, not only the ones published in Terrakube. The mirror serves provider files that an operator has uploaded to the registry storage. Optionally, it fetches missing providers from allowlisted public registries and keeps them (pull-through).

## Client configuration

The mirror URL is scoped to an organization: `https://<registry>/terraform/providers/mirror/v1/<organization>/`. The JSON endpoints need a token for a member of that organization, the same as private modules. Use `terraform login <registry>` or `tofu login <registry>`, or a `credentials` block with a Terrakube token.

Terraform reads `~/.terraformrc` (`%APPDATA%\terraform.rc` on Windows) or the file named in `TF_CLI_CONFIG_FILE`:

```hcl
provider_installation {
  network_mirror {
    url = "https://terrakube-registry.example.com/terraform/providers/mirror/v1/simple/"
  }
}

credentials "terrakube-registry.example.com" {
  token = "<terrakube token>"
}
```

OpenTofu reads `~/.tofurc` (`%APPDATA%\tofu.rc` on Windows) or the file named in `TF_CLI_CONFIG_FILE`. The block is the same:

```hcl
provider_installation {
  network_mirror {
    url = "https://terrakube-registry.example.com/terraform/providers/mirror/v1/simple/"
  }
}
```

Add `include = ["registry.terraform.io/*/*"]` to the `network_mirror` block, plus a `direct {}` block, if only some providers should come from the mirror.

## Endpoints

| Request | Answer |
| --- | --- |
| `GET .../v1/{org}/{hostname}/{namespace}/{type}/index.json` | `{"versions":{"<version>":{}}}` |
| `GET .../v1/{org}/{hostname}/{namespace}/{type}/{version}.json` | `{"archives":{"<os>_<arch>":{"url":"...","hashes":["h1:...","zh:..."]}}}` |
| `GET .../v1/{org}/{hostname}/{namespace}/{type}/{version}/{os}_{arch}.zip?ticket=...` | The archive, or a redirect to a presigned storage URL |

- The JSON endpoints answer `403` when the caller is not a member of the organization, and `401` without a token.
- Archive URLs in `{version}.json` are relative to the document and carry a short-lived download ticket (`io.terrakube.registry.ticket-ttl-seconds`, 300 s by default). Clients send no credentials for the archive itself. A module download ticket is not accepted for a mirror archive, and the other way round.
- When the storage backend presigns URLs (`AwsS3PresignedRedirectEnabled`, `AzureBlobPresignedRedirectEnabled` or `GcpPresignedRedirectEnabled`), the archive request redirects to storage. Otherwise the registry streams it.
- Path parts are checked before use: hostname `[a-z0-9.-]`, namespace and type `[A-Za-z0-9_-]`, version `[0-9A-Za-z.+-]` without `..`, platform `<os>_<arch>`. Anything else is `400`.
- A missing provider, version or archive is `404`. An upstream failure during pull-through is `502`. A stored `{version}.json` that is not valid JSON is `500`, with the file named in the registry log. When every pull-through download slot is busy, the archive request is `503` with `Retry-After: 10`.

## Uploading providers

The mirror reads the layout that `terraform providers mirror` and `tofu providers mirror` write, under the key prefix `mirror/` in the registry storage:

```text
mirror/{hostname}/{namespace}/{type}/index.json
mirror/{hostname}/{namespace}/{type}/{version}.json
mirror/{hostname}/{namespace}/{type}/terraform-provider-{type}_{version}_{os}_{arch}.zip
```

Create the files from a configuration that lists the providers, then copy the output directory under `mirror/`:

```bash
terraform providers mirror -platform=linux_amd64 -platform=darwin_arm64 ./mirror-out
aws s3 cp --recursive ./mirror-out "s3://<AwsStorageBucketName>/mirror/"
```

| Storage | Location |
| --- | --- |
| AWS S3 or S3-compatible | bucket `AwsStorageBucketName`, keys `mirror/...` |
| Azure Blob | container `registry`, blobs `mirror/...` |
| GCS | bucket `GcpStorageBucketName`, objects `mirror/...` |
| Local | `~/.terraform-spring-boot/local/mirror/...` on the registry host |

`index.json` is returned as stored. In `{version}.json` the registry keeps `hashes` and replaces every `url` with its own archive URL; the archive is always read from the key above, whatever the file's `url` says. Upload a new version by writing its files and updating `index.json`. Nothing is cached, so the change is visible on the next request.

Files are stored once for the whole registry, not per organization. The `{org}` part of the URL decides who may read the mirror, but every organization's mirror URL serves the same files. Do not upload a provider that only some organizations may see.

## Pull-through

Pull-through is off by default. When it is on and a provider's hostname matches one of the upstream registries exactly, the registry:

1. Lists versions from the upstream registry API (`/.well-known/terraform.json`, then `{providers.v1}{namespace}/{type}/versions`) for `index.json`.
2. Builds `{version}.json` from the upstream platforms, with `zh:` hashes from the release `SHA256SUMS`.
3. On the first request for an archive that is not in storage, downloads it, checks its SHA-256 against the `shasum` from the upstream registry, and stores it under `mirror/`. A mismatch stores nothing and answers `502`. Later requests are served from storage.

The first download of a platform blocks until the archive is stored, which can take a while for large providers. Each replica runs at most `RegistryMirrorMaxConcurrentFetches` downloads at once; a request for another archive waits up to 10 s for a slot and then gets `503` with `Retry-After`; run `init` again once the running downloads finish. Concurrent requests for the same archive on one replica share one download (and one slot); replicas that download the same archive at the same time write identical objects. Upstream metadata is cached for `RegistryMirrorMetadataCacheTtlSeconds`, so a new upstream release can take that long to appear. The cache holds at most 64 Mi characters of documents per replica. A hostname that is not an upstream is served from uploaded files only.

| Variable | Property | Default | Meaning |
| --- | --- | --- | --- |
| `RegistryMirrorPullThroughEnabled` | `io.terrakube.registry.mirror.pull-through-enabled` | `false` | Fetch missing archives from the upstreams. |
| `RegistryMirrorUpstreams` | `io.terrakube.registry.mirror.upstreams` | `https://registry.terraform.io,https://registry.opentofu.org` | Comma-separated `https` base URLs. The provider hostname must equal the URL's host. |
| `RegistryMirrorMetadataCacheTtlSeconds` | `io.terrakube.registry.mirror.metadata-cache-ttl-seconds` | `600` | How long upstream discovery, version lists and checksums are cached. |
| `RegistryMirrorMaxArchiveSizeMb` | `io.terrakube.registry.mirror.max-archive-size-mb` | `1024` | Larger downloads are aborted and nothing is stored. |
| `RegistryMirrorDownloadTimeoutSeconds` | `io.terrakube.registry.mirror.download-timeout-seconds` | `300` | Deadline for one upstream request, including the body and redirects. |
| `RegistryMirrorMaxConcurrentFetches` | `io.terrakube.registry.mirror.max-concurrent-fetches` | `4` | Archive downloads one replica runs at once. |

The registry needs outbound HTTPS to the upstream registries and to the hosts their `download_url` and `shasums_url` point at.

### Integrity

The registry checks each archive against the SHA-256 in the upstream's download details, and `{version}.json` carries `zh:` hashes from the release `SHA256SUMS`. It does not verify the upstream GPG signature (`SHA256SUMS.sig` and the `signing_keys` of the download details). Integrity rests on fetching the checksums over TLS from an allowlisted upstream: a compromised upstream, or a compromised TLS connection to it, could serve a matching archive and checksum.

### Safeguards for outbound requests

- Only allowlisted upstreams are contacted, and only for providers whose hostname matches one exactly. The scheme and port come from the configuration.
- Every request must be `https`. Redirects are followed by the registry, at most 5, and each hop is checked again.
- A hop whose host resolves to a loopback, private, link-local, unique-local, any-local or multicast address is refused, and so is one in `0.0.0.0/8`, `100.64.0.0/10`, `198.18.0.0/15` or `240.0.0.0/4` (including `255.255.255.255`). For NAT64 (`64:ff9b::/96`), 6to4 (`2002::/16`) and IPv4-compatible (`::a.b.c.d`) IPv6 addresses, the embedded IPv4 address is checked the same way.
- Downloads are capped by `RegistryMirrorMaxArchiveSizeMb`. Connecting times out after 10 s, and every request has the overall deadline above.

Known limit: the address check and the connection resolve the host separately. A DNS answer that changes between the two (DNS rebinding) is not caught. This is acceptable because upstreams come from the operator's allowlist; do not add upstreams you do not trust.

### Storage growth

There is no total size limit for `mirror/`. With pull-through on, any member of any organization can make the registry download and keep any version and platform of a provider from an allowlisted upstream. Each archive is capped by `RegistryMirrorMaxArchiveSizeMb`, but the total is not. Watch the storage size, and use a bucket lifecycle rule or quota if needed. Deleted archives are fetched again on the next request.

## Manual verification

1. Set `RegistryMirrorPullThroughEnabled=true` on the registry.
2. Point a CLI configuration at `https://<registry>/terraform/providers/mirror/v1/<org>/` with credentials, as above.
3. Run `terraform init` and `tofu init` for a configuration that uses `hashicorp/random`.
4. Objects appear under `mirror/registry.terraform.io/hashicorp/random/` and `mirror/registry.opentofu.org/hashicorp/random/`.
5. Delete `.terraform` and run `init` again. The registry log shows no new "Stored mirrored provider archive" line.
