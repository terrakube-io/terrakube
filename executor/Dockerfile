# Adds the command line tools that Terrakube extensions and custom scripts use (git, jq, curl) on
# top of the image built by `mvn -pl executor spring-boot:build-image`. It has no COPY instructions,
# so build it without a context: docker build --build-arg BASE_IMAGE=executor:<version> - < Dockerfile
ARG BASE_IMAGE=executor:latest
FROM ${BASE_IMAGE}

# The buildpack's run user: "cnb" for the Paketo builders, "heroku" for heroku/builder.
ARG RUN_USER=cnb
ARG INSTALL_AWS_CLI=false

USER root
RUN apt-get update \
    && apt-get install -y git jq curl \
    && if [ "${INSTALL_AWS_CLI}" = "true" ]; then \
         apt-get install -y unzip \
         && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o /tmp/awscliv2.zip \
         && unzip -q /tmp/awscliv2.zip -d /tmp \
         && /tmp/aws/install \
         && rm -rf /tmp/aws /tmp/awscliv2.zip; \
       fi \
    && rm -rf /var/lib/apt/lists/*
USER ${RUN_USER}
