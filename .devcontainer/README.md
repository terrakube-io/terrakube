# Terrakube Development Container

This directory contains the configuration for a development container that provides a consistent environment for working with Terrakube. The devcontainer includes all the necessary tools and dependencies to develop both the Java backend, TypeScript frontend components and includes terraform CLI.

> Note: The local VS Code setup below was tested using Ubuntu-based distribution. For macOS and Windows users, GitHub Codespaces is recommended.

## Features

- Java 17 (Liberica)
- Maven 3.9.9
- Node.js 20.x with Yarn
- VS Code extensions for Java, JavaScript/TypeScript

## GitHub Codespaces (Recommended)

The easiest way to get started with Terrakube development is using GitHub Codespaces.

### Getting Started with Codespaces

1. Open the [Terrakube repository](https://github.com/AzBuilder/terrakube) in GitHub
2. Click "Code" → "Codespaces" → "Create codespace on main"
3. Wait for the container to build and start (this may take a few minutes the first time)
4. Services are auto-configured via `postStartCommand`
5. Start all Terrakube components using the VS Code launch configuration

### Codespaces URLs

Once the services are running, access them at:
- **UI**: `https://{codespace-name}-3000.app.github.dev`
- **API**: `https://{codespace-name}-8080.app.github.dev`
- **Registry**: `https://{codespace-name}-8075.app.github.dev`
- **DEX Auth**: `https://{codespace-name}-5556.app.github.dev`

The Codespaces port forwarding panel will show all available ports with their labels.

### Login Credentials

Use the following credentials to log in:
- **Email**: `admin@example.com`
- **Password**: `admin`

## Local VS Code Development

### Prerequisites

- [Visual Studio Code](https://code.visualstudio.com/)
- [VS Code Remote - Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)

#### Local Development Domains

To use the devcontainer we need to setup the following domains in our local computer:

```shell
terrakube.platform.local
terrakube-api.platform.local
terrakube-registry.platform.local
terrakube-dex.platform.local
```

#### HTTPS Local Certificates

Install [mkcert](https://github.com/FiloSottile/mkcert#installation) to generate the local certificates.

To generate local CA certificate execute the following:

```shell
mkcert -install
Created a new local CA 💥
The local CA is now installed in the system trust store! ⚡️
The local CA is now installed in the Firefox trust store (requires browser restart)! 🦊
```

#### Create Docker Network for the devcontainer

```bash
docker network create terrakube-network -d bridge --subnet 10.25.25.0/24 --gateway 10.25.25.254
```

We will be using `10.25.25.253` for our the traefik gateway

#### Local DNS entries

Update the /etc/hosts file adding the following entries:

```bash
10.25.25.253 terrakube.platform.local
10.25.25.253 terrakube-api.platform.local
10.25.25.253 terrakube-registry.platform.local
10.25.25.253 terrakube-dex.platform.local
```

### Opening the Project in a Dev Container

1. Clone the Terrakube repository and run the project:
   ```bash
   git clone https://github.com/AzBuilder/terrakube.git
   cd terrakube/.devcontainer
   mkcert -key-file key.pem -cert-file cert.pem platform.local *.platform.local
   CAROOT=$(mkcert -CAROOT)/rootCA.pem
   cp $CAROOT rootCA.pem
   cd ..
   code .
   ```

2. When prompted to "Reopen in Container", click "Reopen in Container". Alternatively, you can:
   - Press F1 or Ctrl+Shift+P
   - Type "Remote-Containers: Reopen in Container" and press Enter

3. Wait for the container to build and start. This may take a few minutes the first time.

4. Start all Terrakube components

   ![image](https://github.com/user-attachments/assets/34a4d4c9-d1b0-443f-834e-c4d76db26187)

5. Terrakube should be availabe with the following url `https://terrakube.platform.local` using `admin@example.com` with password `admin`

   ![image](https://github.com/user-attachments/assets/c92b5f7a-c484-47b5-bb31-4edd4513278e)

## Ports

The devcontainer forwards the following ports:
- 8080: Terrakube API 
- 8075: Terrakube Registry
- 8090: Terrakube Executor
- 3000: Terrakube UI
- 80: Traefik Gateway

## Customization

You can customize the devcontainer by modifying:
- `.devcontainer/devcontainer.json`: VS Code settings and extensions
- `.devcontainer/Dockerfile`: Container image configuration
