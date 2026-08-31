#=> Build container (node:24-alpine)
FROM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS builder
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
FROM nginxinc/nginx-unprivileged:latest@sha256:197f252f060ed357f2ab98d4256762d7d107c76f18ad8f0b9d5178854611566d

# Default Terraform version, updated at build time
ARG REACT_APP_TERRAKUBE_VERSION=2.0.0

WORKDIR /usr/share/nginx/html

# Static build
COPY --from=builder /app/build .
COPY --from=builder /etc/sbom /etc/sbom
COPY conf/conf.d/terrakube-ui.conf /etc/nginx/conf.d/default.conf
COPY conf/conf.d/gzip.conf /etc/nginx/conf.d/gzip.conf
