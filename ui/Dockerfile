#=> Build container (node:24-alpine)
FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS builder
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

# => Generate SBOM (syft:latest)
COPY --from=anchore/syft:latest@sha256:678bfa565b60f747aac0f8e964fe5588a24445b8d0a480e91f6efd70020dfbb0 /syft /usr/local/bin/syft
RUN mkdir -p /etc/sbom && \
    syft scan dir:/app -o spdx-json=/etc/sbom/sbom.spdx.json -o cyclonedx-json=/etc/sbom/sbom.cdx.json

# => Run container (nginx-unprivileged:latest)
FROM nginxinc/nginx-unprivileged:latest@sha256:9d689e987337916e713a2d5fa84966550d9532e4cb512e7d6ad99df87ffe1ec8

# Default Terraform version, updated at build time
ARG REACT_APP_TERRAKUBE_VERSION=2.0.0

WORKDIR /usr/share/nginx/html

# Static build
COPY --from=builder /app/build .
COPY --from=builder /etc/sbom /etc/sbom
COPY conf/conf.d/terrakube-ui.conf /etc/nginx/conf.d/default.conf
COPY conf/conf.d/gzip.conf /etc/nginx/conf.d/gzip.conf
