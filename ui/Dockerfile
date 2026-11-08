#=> Build container (node:26-alpine, the current LTS; keep in step with ui/.node-version)
FROM node:26-alpine@sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216 AS builder
WORKDIR /app

# Node 25+ no longer bundles Corepack. Install it, then enable it so it picks up
# the Yarn version pinned in package.json's packageManager field.
RUN npm install -g corepack@0.36.0 && corepack enable

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
FROM nginxinc/nginx-unprivileged:latest@sha256:09b0d3eda2aa592d2487f284519419015cab010d7df16cd0fe224b585fc60eb6

# Default Terraform version, updated at build time
ARG REACT_APP_TERRAKUBE_VERSION=2.0.0

WORKDIR /usr/share/nginx/html

# Static build
COPY --from=builder /app/build .
COPY --from=builder /etc/sbom /etc/sbom
COPY conf/conf.d/terrakube-ui.conf /etc/nginx/conf.d/default.conf
COPY conf/conf.d/gzip.conf /etc/nginx/conf.d/gzip.conf
