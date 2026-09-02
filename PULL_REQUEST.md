# Pull Request Title

```text
feat(registry): presigned URL redirect for AWS S3, Azure Blob and GCP Cloud Storage + S3 resilience improvements from #3451
```

---

# Pull Request Body

```markdown
## Summary

This PR extends the presigned-redirect feature originally implemented for AWS S3 in #3451 to **Azure Blob Storage** (SAS URLs) and **GCP Cloud Storage** (V4-signed URLs), and cherry-picks the full S3 resilience improvements from that PR.

All three presigned redirect features are **opt-in and off by default**, controlled by per-backend feature flags. The existing byte-proxy path is fully preserved as a rollback mechanism.

---

## Related PR

> This work builds directly on [#3451 – feat(registry): make module download resilient to concurrent load and S3 latency](https://github.com/terrakube-io/terrakube/pull/3451) by @jakegroves.
> The S3 implementation from that PR is cherry-picked here without modification; this PR extends the same pattern to the Azure and GCP backends.

---

## Changes

### 1. Cherry-picked from #3451 (AWS S3 — unchanged)
- **Thundering-Herd Protection**: `@Cacheable(sync = true)` on `ModuleServiceImpl.getModuleVersionPath` coalesces concurrent cache misses for the same module version into a single resolution.
- **Asynchronous Download Count Bookkeeping**: Moves download-count increments onto a dedicated bounded `downloadCountExecutor` thread pool with retry and exponential backoff, keeping it off the client response critical path.
- **Presigned S3 URL Redirect** (`presignedRedirectEnabled`, default `false`): Redirects (`302 Found`) `/download/.../module.zip` requests to short-lived presigned S3 GET URLs instead of proxying bytes through the registry pod.
- **Resilient Error Mapping**: Replaced silent empty `byte[0]` returns on S3 failures with `StorageUnavailableException`, mapped to `503 Service Unavailable` + `Retry-After: 5` via `StorageExceptionHandler`.
- **Configurable S3 Client Limits**: Added configurable connection pool limits and timeouts (`connectionAcquisitionTimeoutSeconds`, `connectTimeoutSeconds`, `socketTimeoutSeconds`, `apiCallAttemptTimeoutSeconds`, `apiCallTimeoutSeconds`, `maxConnections`).

### 2. Azure Blob Storage (New)
- `AzureStorageServiceProperties`: Adds `presignedRedirectEnabled` (default `false`) and `presignedUrlExpirySeconds` (default `300`).
- `AzureStorageServiceImpl.getPresignedDownloadUrl()`: Generates read-only SAS tokens using `BlobSasPermission.setReadPermission(true)` and `BlobServiceSasSignatureValues`.
  - **Clock Skew Tolerance**: `startTime` is set to `now() - 60s` to prevent `AuthenticationFailed: Signature not valid yet` errors if registry and client clocks are slightly skewed.
  - `BlobStorageException` in `searchModule` and `downloadModule` is wrapped in `StorageUnavailableException` to prevent 0-byte corrupt ZIP downloads.
- **Environment Variables**:
  - `AzureBlobPresignedRedirectEnabled=true` (default `false`)
  - `AzureBlobPresignedUrlExpirySeconds=300` (default `300`)

### 3. GCP Cloud Storage (New)
- `GcpStorageServiceProperties`: Adds `presignedRedirectEnabled` (default `false`) and `presignedUrlExpirySeconds` (default `300`).
- `GcpStorageServiceImpl.getPresignedDownloadUrl()`: Generates V4-signed URLs using `storage.signUrl(..., Storage.SignUrlOption.withV4Signature())`.
  - `StorageException` and `IOException` in `searchModule` and `downloadModule` are wrapped in `StorageUnavailableException`.
- **Environment Variables**:
  - `GcpPresignedRedirectEnabled=true` (default `false`)
  - `GcpPresignedUrlExpirySeconds=300` (default `300`)

### 4. Configuration & AutoConfiguration
- `StorageAutoConfiguration`: Wires `presignedUrlExpirySeconds` and `presignedRedirectEnabled` to both `AzureStorageServiceImpl` and `GcpStorageServiceImpl` builders.
- `application.properties`: Added placeholders and defaults for Azure and GCP presigned settings.

---

## How It Works

```text
Terraform CLI
  │
  │  GET /terraform/modules/v1/{org}/{mod}/{prov}/{ver}/download
  │  ← 204 No Content  (X-Terraform-Get: /download/.../module.zip)
  │
  │  GET /terraform/modules/v1/download/.../module.zip
  │      ├─ presignedRedirectEnabled=true  → 302 Found  (Location: <presigned URL>)
  │      │                                   Terraform downloads ZIP directly
  │      │                                   from S3 / Azure Blob / GCS
  │      └─ presignedRedirectEnabled=false → 200 OK  (bytes proxied, legacy path)
```

---

## S3-Compatible Storage (MinIO / LocalStack)

Presigned redirects are fully supported with custom S3-compatible endpoints. When `AwsEndpoint` is configured, both `S3Client` and `S3Presigner` use `pathStyleAccessEnabled(true)` with the same `endpointOverride`.

> **Note**: Ensure the Terraform client has network reachability to the endpoint hostname specified in the presigned URL.

---

## Rollout & Rollback Flags

| Backend | Environment Variable | Default |
|:---|:---|:---|
| **AWS S3** | `AwsS3PresignedRedirectEnabled` | `false` |
| **Azure Blob** | `AzureBlobPresignedRedirectEnabled` | `false` |
| **GCP Cloud Storage** | `GcpPresignedRedirectEnabled` | `false` |

All backends default to `false` (legacy proxying). Setting any flag back to `false` instantly reverts to the byte-proxy mode.

---

## Verification & Automated Tests

All 56 registry unit and integration tests pass cleanly (`mvn test -pl registry -am`):

- `AzureStorageServiceImplPresignedTest` (9 tests): Covers redirect flag off/on, SAS generation, clock skew `startTime`, exception wrapping, and upload paths.
- `GcpStorageServiceImplPresignedTest` (8 tests): Covers redirect flag off/on, V4 signature generation, exception wrapping, and upload paths.
- `AwsStorageServiceImplTest` (8 tests): Covers S3 presigned redirects and error wrapping.
- `ModuleServiceImplCacheTest` (2 tests): Validates `@Cacheable(sync=true)` thundering-herd mitigation under concurrent requests.
- `ModuleServiceImplAsyncDownloadCountTest` (2 tests): Validates async download-count executor and retry/backoff.
- `ModuleWebServiceImplTest` (3 tests): Validates `302 Found` redirects, byte-proxying, and `503 Service Unavailable` error handling.
```
