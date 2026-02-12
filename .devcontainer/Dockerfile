FROM mcr.microsoft.com/devcontainers/java:dev-17-bookworm

# Install necessary packages
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends \
    curl \
    git \
    wget \
    gnupg \
    lsb-release \
    ca-certificates \
    apt-transport-https \
    software-properties-common \
    unzip \
    zip \
    maven \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

ENV ACCEPT_EULA=Y

# Install mssql-tools18 only on amd64 (not available for arm64)
RUN if [ "$(dpkg --print-architecture)" = "amd64" ]; then \
    curl -sSL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor -o /etc/apt/keyrings/microsoft.gpg && \
    echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/microsoft.gpg] https://packages.microsoft.com/debian/12/prod bookworm main" > /etc/apt/sources.list.d/microsoft-prod.list && \
    apt-get update && \
    apt-get install -y mssql-tools18 unixodbc-dev && \
    ln -s /opt/mssql-tools18/bin/* /usr/local/bin/; \
    else \
    echo "Skipping mssql-tools18 installation (not available for arm64)"; \
    fi

#RUN wget -O - https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg && \
#    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(grep -oP '(?<=UBUNTU_CODENAME=).*' /etc/os-release || lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list && \
#    apt update && \
#    apt install vault && \
#    setcap -r /usr/bin/vault

# Install OpenBao with architecture detection
RUN ARCH=$(dpkg --print-architecture) && \
    if [ "$ARCH" = "amd64" ]; then BAO_ARCH="amd64"; \
    elif [ "$ARCH" = "arm64" ]; then BAO_ARCH="arm64"; \
    else echo "Unsupported architecture: $ARCH" && exit 1; fi && \
    wget https://github.com/openbao/openbao/releases/download/v2.2.2/bao_2.2.2_linux_${BAO_ARCH}.deb && \
    dpkg -i bao_2.2.2_linux_${BAO_ARCH}.deb && \
    rm bao_2.2.2_linux_${BAO_ARCH}.deb

COPY rootCA.pem /usr/local/share/ca-certificates/root.crt

RUN update-ca-certificates


ENTRYPOINT ["sleep", "infinity"]
