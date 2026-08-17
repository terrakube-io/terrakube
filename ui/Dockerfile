#=> Build container
FROM node:24-alpine AS builder
WORKDIR /app

# Enable Corepack so it picks up the Yarn version pinned in
# package.json's packageManager field.
RUN corepack enable

COPY package.json yarn.lock .yarnrc.yml ./

# Install dependencies using the exact versions from yarn.lock
RUN yarn install --immutable
COPY . .

# Increase Node.js memory limit to prevent out of memory errors
ENV NODE_OPTIONS="--max-old-space-size=4096"

RUN yarn build

# => Generate SBOM
COPY --from=anchore/syft:latest /syft /usr/local/bin/syft
RUN mkdir -p /etc/sbom && \
    syft scan dir:/app -o spdx-json=/etc/sbom/sbom.spdx.json -o cyclonedx-json=/etc/sbom/sbom.cdx.json

# => Run container
FROM nginxinc/nginx-unprivileged:latest

# Default Terraform version, updated at build time
ARG REACT_APP_TERRAKUBE_VERSION=2.0.0

WORKDIR /usr/share/nginx/html

# Static build
COPY --from=builder /app/build .
COPY --from=builder /etc/sbom /etc/sbom
COPY conf/conf.d/terrakube-ui.conf /etc/nginx/conf.d/default.conf