# Registry caching and revalidation

The registry answers module and provider lookups from short-lived server caches and lets clients revalidate metadata with `ETag` and `If-None-Match`. This page lists what is cached, for how long, and how fresh a response can be.

## Server caches

Each registry replica keeps its own in-memory caches.

| Cache | Holds | Lifetime |
| --- | --- | --- |
| Module versions | Version list of one module | `ModuleVersionsCacheTtlSeconds`, default 600 s |
| Module download path | Resolved path of one module version | 10 min |
| Removed versions | Whether a module version was removed | 30 s |
| Module details | Parsed inputs, outputs and resources of one module version | 10 min |
| Provider versions | Version list and platforms of one provider | `ProviderCacheTtlSeconds`, default 60 s |
| Provider file information | Download details of one provider version, os and arch | `ProviderCacheTtlSeconds`, default 60 s |
| Provider warnings | Deprecation notice of one provider | 10 min |
| Organization id | Organization name to id | 60 s (not configurable) |

A missing organization is never cached, so it is looked up again on the next request. Each cache holds at most 1000 entries (10000 for removed versions).

## HTTP revalidation

The registry sends an `ETag` on successful `GET` responses of the discovery, module, provider and readme endpoints. A request with a matching `If-None-Match` gets `304 Not Modified` without a body. Terraform and OpenTofu do not send `If-None-Match`, so their behavior is unchanged; the headers help browsers, proxies and other clients.

| Response | `Cache-Control` | Other headers |
| --- | --- | --- |
| Module versions, module details, readme | `no-cache, private` | `ETag`, `Vary: Authorization` |
| Provider versions and provider download details | `no-cache, private` | `ETag`, `Vary: Authorization` |
| `/.well-known/terraform.json` | `max-age=300, public` | `ETag` |
| Module `/download` (carries a download ticket) | `no-store` | no `ETag` |
| `module.zip` | `no-store` | no `ETag` |

* `no-cache, private` means a client may keep the response but must revalidate it before reuse, and shared caches must not store it.
* The `ETag` filter runs after authentication and authorization. A `304` is only ever answered to a request that is allowed to read the resource, so an `ETag` learned under one `Authorization` header never produces a `304` for another.

## How fresh a response is

The `ETag` is computed over the response body, and the body comes from the server caches above. Revalidation therefore never shows data fresher than the cache behind it:

* A module version list can be up to `ModuleVersionsCacheTtlSeconds` (600 s by default) behind, on each replica. A removed version stops being downloadable within 30 s, and the replica that serves such a request drops its cached list.
* A provider version list or download detail can be up to `ProviderCacheTtlSeconds` (60 s by default) behind. That works both ways: a new version can take that long to appear, and a removed version can still be listed and its download details served for that long.
* Renaming or recreating an organization can serve the old id for up to 60 s.

## Module download counts

Each registry replica counts module downloads in memory and writes them to the API every `DownloadCountFlushIntervalSeconds` (30 s by default): one read and one update per downloaded module, however many downloads it had. Before, every download cost one read and one update. The count on the module page can therefore lag by up to one interval.

The count is a display figure and can come out a little low:

* A graceful shutdown writes the pending counts, for at most 10 seconds (plus one API call timeout); counts it could not write in that time are dropped with a warning. A registry that is killed (`SIGKILL`, out of memory) loses up to one interval of its counts.
* The update is a read followed by a write of the new total. When two replicas write the same module at the same moment, one overwrites the other and up to one interval of that replica's counts is lost.
* When the API is unreachable, the counts are kept and retried on the next interval. Counts for a module or organization that was deleted are dropped.

## Configuration

| Setting | Environment variable | Default |
| --- | --- | --- |
| `io.terrakube.registry.moduleVersionsCacheTtlSeconds` | `ModuleVersionsCacheTtlSeconds` | `600` |
| `io.terrakube.registry.providerCacheTtlSeconds` | `ProviderCacheTtlSeconds` | `60` |
| `io.terrakube.registry.download-count.flush-interval-seconds` | `DownloadCountFlushIntervalSeconds` | `30` |

The cache lifetimes must be at least 1. Lower values make new versions show up sooner and increase the load on the API.

## Upgrade notes

No required action.

* Responses carry the new headers and provider lookups are cached for 60 s.
* Download counts are written every 30 s instead of on each download. `DownloadCountExecutorCorePoolSize`, `DownloadCountExecutorMaxPoolSize` and `DownloadCountExecutorQueueCapacity` are no longer read and can be removed.
* On startup the API adds two indexes: `idx_team_organization_id` on `team(organization_id)`, used by permission checks, and `idx_federated_issuer_url` on `federated(issuer_url)`, used to look up federated credentials on each request. Both tables are small, so the migration is quick. An index of the same name that was added by hand is kept.
* Listing provider versions loads the platforms of up to 100 versions in one query instead of one query per version.
