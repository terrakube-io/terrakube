# Terrakube Development Container

This directory contains the configuration for a development container that provides a consistent environment for working with Terrakube. The devcontainer includes all the necessary tools and dependencies to develop both the Java backend, TypeScript frontend components and includes terraform CLI.

> **Note for GitHub Codespaces Users:**
> Certificate generation is now automatic! The devcontainer will automatically generate self-signed certificates when you open the project in Codespaces. You can skip the manual certificate generation steps in the "HTTPS Local Certificates" section below.

> **Note for Local Development:**
> The instructions below were tested using Ubuntu-based distributions. Compatibility with macOS and Windows may vary.

## Features

- Java 17 (Liberica)
- Maven 3.9.9
- Node.js 20.x with Yarn
- VS Code extensions for Java, JavaScript/TypeScript

## Getting Started

### Option 1: GitHub Codespaces (Recommended for Quick Start)

GitHub Codespaces provides a cloud-based development environment with automatic setup:

1. Click on "Code" → "Codespaces" → "Create codespace on main"
2. Wait for the environment to build (certificates will be generated automatically)
3. Once ready, Terrakube will be available at the forwarded ports
4. Login with `admin@example.com` and password `admin`

**Note:** In Codespaces, the local domains (*.platform.local) won't work as expected. You'll need to use the forwarded port URLs provided by Codespaces instead.

### Option 2: Local Development with VS Code

#### Prerequisites

- [Visual Studio Code](https://code.visualstudio.com())
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

The Docker network is required for the devcontainer to work properly:

```bash
docker network create terrakube-network -d bridge --subnet 10.25.25.0/24 --gateway 10.25.25.254
```

We will be using `10.25.25.253` for the traefik gateway.

**Note:** If you use the automated initialization script (recommended), the network will be created automatically.

#### Local DNS entries

Update the /etc/hosts file adding the following entries:

```bash
10.25.25.253 terrakube.platform.local
10.25.25.253 terrakube-api.platform.local
10.25.25.253 terrakube-registry.platform.local
10.25.25.253 terrakube-dex.platform.local
```

### Opening the Project in a Dev Container

1. Clone the Terrakube repository:
   ```bash
   git clone https://github.com/AzBuilder/terrakube.git
   cd terrakube
   ```

2. Initialize the environment (choose one method):

   **Method A - Automatic initialization (recommended):**
   This script creates the Docker network and generates self-signed certificates:
   ```bash
   bash .devcontainer/init-devcontainer.sh
   ```

   **Method B - Manual setup with mkcert:**
   Create the Docker network and generate certificates using mkcert:
   ```bash
   # Create Docker network
   docker network create terrakube-network -d bridge --subnet 10.25.25.0/24 --gateway 10.25.25.254

   # Generate certificates with mkcert
   cd .devcontainer
   mkcert -key-file key.pem -cert-file cert.pem platform.local *.platform.local
   CAROOT=$(mkcert -CAROOT)/rootCA.pem
   cp $CAROOT rootCA.pem
   cd ..
   ```

3. Open in VS Code:
   ```bash
   code .
   ```

4. When prompted to "Reopen in Container", click "Reopen in Container". Alternatively, you can:
   - Press F1 or Ctrl+Shift+P
   - Type "Remote-Containers: Reopen in Container" and press Enter

5. Wait for the container to build and start. This may take a few minutes the first time.

6. Start all Terrakube components

   ![image](https://github.com/user-attachments/assets/34a4d4c9-d1b0-443f-834e-c4d76db26187)

7. Terrakube should be available at `https://terrakube.platform.local` using `admin@example.com` with password `admin`

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
