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

RUN curl -sSL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor -o /etc/apt/keyrings/microsoft.gpg && \
    echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/microsoft.gpg] https://packages.microsoft.com/debian/12/prod bookworm main" > /etc/apt/sources.list.d/microsoft-prod.list && \
    apt-get update && \
    apt-get install -y mssql-tools18 unixodbc-dev && \
    ln -s /opt/mssql-tools18/bin/* /usr/local/bin/

#RUN wget -O - https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg && \
#    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(grep -oP '(?<=UBUNTU_CODENAME=).*' /etc/os-release || lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list && \
#    apt update && \
#    apt install vault && \
#    setcap -r /usr/bin/vault

RUN wget https://github.com/openbao/openbao/releases/download/v2.2.2/bao_2.2.2_linux_amd64.deb && \
    dpkg -i bao_2.2.2_linux_amd64.deb

ARG INSTALL_CA=false
RUN --mount=type=bind,source=rootCA.pem,target=/tmp/rootCA.pem,required=false sh -c '\
  if [ "$INSTALL_CA" = "true" ] && [ -f /tmp/rootCA.pem ]; then \
    install -D /tmp/rootCA.pem /usr/local/share/ca-certificates/root.crt && \
    update-ca-certificates; \
  else \
    echo "Certificado omitido (INSTALL_CA=$INSTALL_CA, existe=$( [ -f /tmp/rootCA.pem ] && echo si || echo no ))"; \
  fi \
'


RUN update-ca-certificates


ENTRYPOINT ["sleep", "infinity"]
