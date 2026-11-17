# Registry direct storage download

By default the registry answers a module download request with `X-Terraform-Get` pointing at its own `module.zip` URL (with a short-lived ticket). That URL then redirects to object storage or streams the zip. With `RegistryDirectStorageDownload=true` the registry puts the presigned storage URL straight into `X-Terraform-Get`, so Terraform and OpenTofu fetch the zip from storage and the extra registry round trip is gone.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `RegistryDirectStorageDownload` | `false` | Use the presigned storage URL in `X-Terraform-Get`. |

The flag only takes effect when the storage backend already presigns URLs. Enable the backend flag as well:

| Backend | Enable | Expiry (default 300 s) | Prerequisites |
| --- | --- | --- | --- |
| AWS S3 | `AwsS3PresignedRedirectEnabled=true` | `AwsS3PresignedUrlExpirySeconds` | Registry credentials may `s3:GetObject` on the bucket; clients can reach the S3 endpoint. |
| Azure Blob | `AzureBlobPresignedRedirectEnabled=true` | `AzureBlobPresignedUrlExpirySeconds` | Registry uses a shared-key (connection string) credential; clients can reach the blob endpoint. |
| GCS | `GcpPresignedRedirectEnabled=true` | `GcpPresignedUrlExpirySeconds` | Registry credentials can sign (service account key, or `iam.serviceAccounts.signBlob`); clients can reach `storage.googleapis.com`. |

## Behavior

- The presigned URL is created for every request and never cached. Its lifetime is the backend's presign expiry; a client must start the download before it expires. There is no registry-side limit on the value.
- With the flag off, with local storage, with the backend presign flag off, or when presigning fails, the registry returns its usual ticket URL and the previous flow applies (redirect to storage or proxy the bytes).
- Direct URLs bypass the registry for the download itself, so the registry no longer sees `module.zip` requests for them. The download counter is still updated when `/download` is called.
- The `/download` response keeps `Cache-Control: no-store`.
- The URL is a plain `https://` URL; no getter prefix is added.

## Manual verification

1. Set the backend presign flag and `RegistryDirectStorageDownload=true` on the registry.
2. Run `terraform init` and `tofu init` against a configuration that uses a module from the registry.
3. Both must succeed, and the registry log must show the `/download` call but no `module.zip` request.
4. Set `RegistryDirectStorageDownload=false` and repeat; `module.zip` requests return.
