# Terrakube development container

This is the supported local development environment for macOS (Apple Silicon
and Intel), Linux, Windows, and WSL. The tools and dependencies run in Linux
containers, so contributors use the same workflow on every host platform.

## Prerequisites

- VS Code and the **Dev Containers** extension
- A container runtime with Docker Compose support
- [mkcert](https://github.com/FiloSottile/mkcert) on the host
- 4 CPUs and 8 GB RAM available to the container runtime

On Windows, use Docker Desktop's WSL integration and clone inside the WSL
filesystem (for example, `~/src/terrakube`), not `/mnt/c`. This avoids slow
bind mounts and unreliable file watching.

No custom Docker subnet, local DNS server, or hosts-file entries are required.
The `*.localhost` names used by the environment resolve to the loopback address
on supported browsers and operating systems.

## Start developing

1. Follow the certificate step below once.
2. Clone your fork, open it in VS Code, and choose **Dev Containers: Reopen in
   Container** when prompted.
3. Once setup finishes, select **Terrakube Postgresql** in Run and Debug.
4. Open <https://terrakube.localhost> and sign in with `admin@example.com` /
   `admin`.

PostgreSQL, MinIO, Redis, and Traefik start with the Dev Container. Do not run
`docker compose up` or **setup-env2** yourself.

## Make and debug changes

Use only **Terrakube Postgresql** for the normal local stack. **Terrakube** is
an alternative H2 stack: never run both compounds, because they start the same
applications on the same ports.

- UI changes refresh the browser automatically.
- For API, Registry, or Executor changes, use hot-code replace when available;
  otherwise restart just that service's **Run** configuration.
- Check the changed behaviour at <https://terrakube.localhost>.

Rebuild the Dev Container only after changing `.devcontainer/`, its Dockerfile,
features, or trusted CA files.

## Validate before a pull request

Run the checks relevant to the change from the repository root:

```sh
# Backend or shared changes
mvn -B verify -Dspring-boot.build-image.skip=true

# UI changes
cd ui
yarn install --immutable
yarn lint:modules:check
yarn format:modules:check
yarn build
```

Then use **Terrakube Postgresql** to verify the affected user flow. Record what
you ran and checked in the pull request. See [CONTRIBUTING.md](../CONTRIBUTING.md).

Traefik publishes ports 80 and 443 by default. If either is already occupied,
set `TRAEFIK_HTTP_PORT` and `TRAEFIK_HTTPS_PORT` in `.devcontainer/.env` before
rebuilding. The app URLs and OAuth redirects expect HTTPS on port 443, so use
the default ports for the full sign-in flow.

## One-time certificate setup

Terrakube uses a local mkcert certificate. Install mkcert using its upstream
instructions for your platform, then trust its local CA once on the host:

```sh
mkcert -install
```

Generate the certificate before opening the Dev Container:

```sh
mkdir -p .devcontainer/tls
mkcert -cert-file .devcontainer/tls/cert.pem -key-file .devcontainer/tls/key.pem \
  localhost terrakube.localhost terrakube-api.localhost \
  terrakube-registry.localhost terrakube-executor.localhost \
  terrakube-dex.localhost
cp "$(mkcert -CAROOT)/rootCA.pem" .devcontainer/tls/rootCA.pem
```

Restart the browser after the one-time CA installation. If organisation policy
blocks `mkcert -install`, follow your organisation's process for trusting a
development CA; this cannot be automated portably without changing host trust
settings.

### Corporate HTTPS inspection

If your company proxies or inspects HTTPS traffic, Maven and other tooling need
the company CA trusted inside the Dev Container. Put the approved root or
intermediate CA in [certs](certs/README.md), then run **Dev Containers: Rebuild
Container**. This also supplies the trusted store to Java tools bundled with VS
Code, including the Java language server. The certificate remains local and is
not committed.

## Architecture support

The Dev Container no longer pins `linux/amd64`. Docker pulls arm64 images on
Apple Silicon and amd64 images elsewhere. The optional SQL Server command-line
tools remain amd64-only and are skipped on arm64; they are not required for the
default PostgreSQL/MinIO workflow.
