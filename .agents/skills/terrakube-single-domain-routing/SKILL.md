---
name: terrakube-single-domain-routing
description: >-
  Guidelines and verification steps for keeping docs/single-domain/route-table.yaml
  aligned with API, Registry, and UI endpoints. Use when adding, moving, or removing
  Spring MVC controllers in api or registry, adding top-level UI routes, or fixing
  RouteTableDriftTests or scripts/check-route-table.py CI failures.
---

# Terrakube Single-Domain Route Table Guide

`docs/single-domain/route-table.yaml` is the shared-host routing contract enforced in CI by:
- `api/src/test/java/io/terrakube/api/RouteTableDriftTests.java`
- `registry/src/test/java/io/terrakube/registry/RouteTableDriftTests.java`
- `scripts/check-route-table.py`

## When `route-table.yaml` Must Be Updated

Update `docs/single-domain/route-table.yaml` whenever:
1. **New backend root path**: You add a controller in `api` or `registry` whose URL path is not already covered by an entry in that component's `served_roots` (`path.equals(root) || path.startsWith(root + "/")`).
   - Example: Adding `/internal/v1/tokens/{tokenId}/status` requires `- /internal/v1` under `api`'s `served_roots`.
2. **Removed or renamed backend root**: You remove or move the last controller under an existing `served_roots` entry (otherwise `RouteTableDriftTests` fails with `listed in route-table.yaml served_roots but not served`).
3. **New top-level shared-host prefix or UI route**: You introduce a new top-level prefix outside `/api/` or `/api/private-registry/`, or add a UI route in `ui/src/domain/Home/App.tsx` whose first segment collides with a backend prefix.

You do **not** need to update `route-table.yaml` when:
- Adding endpoints under an existing root (e.g. `/api/v1/...`, `/admin/v1/...`, `/internal/v1/...`).
- Adding `/actuator/**` or `/error` handlers (excluded by `RouteTableDriftTests`).

## How to Add a New Endpoint Root

### Case A: Standard API or Registry Endpoint (under `/api/` or `/api/private-registry/`)
1. Open `docs/single-domain/route-table.yaml`.
2. Locate the `component: api` (or `component: registry`) route entry.
3. Add the root prefix (e.g. `- /internal/v1`) to `served_roots:`.
   - New root paths are automatically served on the shared host as `/api/<root>`.

### Case B: New Top-Level Shared-Host Prefix
1. Add a new entry under `routes:` in `docs/single-domain/route-table.yaml` (ordered most-specific first).
2. Add the matching Traefik `Path`/`PathPrefix` rule in `docker-compose/docker-compose.single-domain.yml`.
3. If legacy aliases are involved when `TerrakubePublicUrl` is set, keep `CanonicalPathForwardFilter` in sync between `api` and `registry`.

## Verification

Run all applicable checks before committing:

```bash
# 1. Validate route-table.yaml against docker-compose and UI routes
python3 scripts/check-route-table.py

# 2. Verify API endpoint coverage (if api endpoints changed)
cd api && mvn -o test -Dtest=RouteTableDriftTests

# 3. Verify Registry endpoint coverage (if registry endpoints changed)
cd registry && mvn -o test -Dtest=RouteTableDriftTests
```
