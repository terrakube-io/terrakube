#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."

# Builds the Terrakube images locally and tags them azbuilder/<image>:latest.
# VERSION defaults to the latest git tag; INSTALL_AWS_CLI=true adds the AWS CLI to the executor.
VERSION="${VERSION:-$(git describe --tags --abbrev=0 2>/dev/null || echo 0.0.0-SNAPSHOT)}"
INSTALL_AWS_CLI="${INSTALL_AWS_CLI:-false}"

# Build and test the Java modules
mvn clean install -Drevision="$VERSION" -Dspring-boot.build-image.skip=true

# Build Terrakube Images
mvn -pl "api,registry,executor" spring-boot:build-image -B --file pom.xml -Dmaven.test.skip=true -Drevision="$VERSION"

# Add the tools that Terrakube extensions use to the executor image
RUN_USER="$(docker inspect -f '{{.Config.User}}' "executor:$VERSION")"
docker build --build-arg BASE_IMAGE="executor:$VERSION" --build-arg RUN_USER="$RUN_USER" --build-arg INSTALL_AWS_CLI="$INSTALL_AWS_CLI" \
  -t azbuilder/executor:latest - < executor/Dockerfile

# Setup docker tags
docker tag "api-server:$VERSION" azbuilder/api-server:latest
docker tag "open-registry:$VERSION" azbuilder/open-registry:latest

# Build Terrakube UI Image
cd ui
docker build -t azbuilder/terrakube-ui:latest .
