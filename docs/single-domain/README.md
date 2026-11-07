# Single-domain route table

Single-domain deployment (experimental in 2.35) serves the UI, API, registry and Dex from one
hostname, routed by path. Setup, configuration and migration are documented at
[docs.terrakube.io](https://docs.terrakube.io/getting-started/deployment/single-domain).

[`route-table.yaml`](route-table.yaml) is the routing contract for the shared host. These read it:

- `RouteTableDriftTests` in `api` and `registry`: every served endpoint must sit under the
  component's `served_roots`, and every listed root must be served.
- `scripts/check-route-table.py` (run in CI): the Traefik rules in
  `docker-compose/docker-compose.single-domain.yml` must route every entry to its component, no
  prefix may belong to two components, and no UI route may start with a backend prefix.

When you add a controller under a new root path, add the root to the api (or registry) entry's
`served_roots`. New root paths are served on the shared host as `/api/<root>` automatically.
