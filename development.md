# Terrakube development environments

## Local development (recommended)

Use the [Dev Container guide](.devcontainer/README.md) for macOS, Linux,
Windows, and WSL. It is the supported contributor workflow and provides the
debugger, local services, and browser URLs.

## GitHub Codespaces

Codespaces uses the same Dev Container configuration when a local container
runtime is not available.

1. Fork Terrakube and create a Codespace from your fork. Choose at least 4
   CPUs when available.
2. Wait for the Dev Container setup to finish.
3. In Run and Debug, start **Terrakube Postgresql**. Do not also start
   **Terrakube**; it is the alternative H2 configuration.
4. Use the URLs in the generated `DEVCONTAINER.md` file. If GitHub asks, make
   the UI, API, Registry, Executor, and Dex ports public so the login redirect
   can complete.
5. Sign in with `admin@example.com` / `admin` and verify your change.

Before opening a pull request, run the relevant checks and record the local or
Codespaces testing in the pull request description. See
[CONTRIBUTING.md](CONTRIBUTING.md).
