#!/bin/bash

# Install Java Dependencies
mvn clean install

# Build skip spring boot docker image
mvn clean install -Dspring-boot.build-image.skip=true

# Update Terrakube Version
mvn -pl "api,registry,executor" versions:set-property -Dproperty=revision -DnewVersion=$VERSION -DgenerateBackupPoms=false

# Build Terrakube Images
mvn -pl "api,registry,executor" spring-boot:build-image -B  --file pom.xml

# Install other dependencies to use with Terrakube Extensions in terrakube executor creating a temporal image
if [ "$INSTALL_AWS_CLI" = "true" ]; then
  docker run --user="root" --entrypoint launcher $(docker images executor -q) "apt-get update && apt-get install git jq curl unzip -y && curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip && unzip awscliv2.zip && ./aws/install"
else
  docker run --user="root" --entrypoint launcher $(docker images executor -q) "apt-get update && apt-get install git jq curl -y"
fi

# Rollback to original entry point
docker commit --change='ENTRYPOINT ["/cnb/process/web"]' --change='USER cnb' $(docker ps -lq) executortemp

# Setup docker tags
docker tag $(docker images api-server -q) azbuilder/api-server:latest
docker tag $(docker images open-registry -q) azbuilder/open-registry:latest
docker tag $(docker images executortemp -q) azbuilder/executor:latest

# Build Terrakube UI Image
cd ui 

# Install UI dependencies
yarn install

# Build docker image
docker build -t terrakube-ui:latest  . 

# Setup tags for UI
docker tag $(docker images terrakube-ui -q) azbuilder/terrakube-ui:latest


