#!/bin/bash

function generateApiVars(){
  USER=$(whoami)
  if [ "$USER" = "gitpod" ]; then
    TerrakubeHostname=$(gp url 8080 | sed "s+https://++g")
    AzBuilderExecutorUrl="$(gp url 8090)/api/v1/terraform-rs"
    DexIssuerUri="$(gp url 5556)/dex"
    TerrakubeUiURL=$(gp url 3000)
  else
    TerrakubeHostname="http://localhost:8080"
    AzBuilderExecutorUrl="http://localhost:8090/api/v1/terraform-rs"
    DexIssuerUri="http://localhost:5556/dex"
    TerrakubeUiURL="http://localhost:3000"
  fi

  ApiDataSourceType="H2"
  GroupValidationType="DEX"
  UserValidationType="DEX"
  AuthenticationValidationType="DEX"
  PatSecret=ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=
  InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
  TERRAKUBE_ADMIN_GROUP="CUSTOM_ADMIN_NAME"

  StorageType="AZURE"
  DexClientId="example-app"

  TerrakubeToolsRepository=https://github.com/AzBuilder/terrakube-extensions.git
  TerrakubeToolsBranch=main

  JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m"

  rm -f .envApi

  echo "ApiDataSourceType=$ApiDataSourceType" >> .envApi
  echo "GroupValidationType=$GroupValidationType" >> .envApi
  echo "UserValidationType=$UserValidationType" >> .envApi
  echo "AuthenticationValidationType=$AuthenticationValidationType" >> .envApi
  echo "TerrakubeHostname=$TerrakubeHostname" >> .envApi
  echo "AzBuilderExecutorUrl=$AzBuilderExecutorUrl" >> .envApi 
  echo "PatSecret=$PatSecret" >> .envApi
  echo "InternalSecret=$InternalSecret" >> .envApi
  echo "DexIssuerUri=$DexIssuerUri" >> .envApi
  echo "StorageType=$StorageType" >> .envApi

  echo "AzureAccountName=$AZURE_STORAGE_NAME" >> .envApi
  echo "AzureAccountKey=$AZURE_STORAGE_KEY" >> .envApi

  echo "TerrakubeUiURL=$TerrakubeUiURL" >> .envApi
  echo "spring_profiles_active=demo" >> .envApi
  echo "DexClientId=$DexClientId" >> .envApi
  echo "TerrakubeToolsRepository=$TerrakubeToolsRepository" >> .envApi
  echo "TerrakubeToolsBranch=$TerrakubeToolsBranch" >> .envApi
  echo "CustomTerraformReleasesUrl=\"https://releases.hashicorp.com/terraform/index.json\"" >> .envApi
  echo "CustomTofuReleasesUrl=\"https://api.github.com/repos/opentofu/opentofu/releases\"" >> .envApi
  echo "TerrakubeRedisHostname=localhost" >> .envApi
  echo "TerrakubeRedisPort=6379" >> .envApi
  echo "TerrakubeRedisSSL=false" >> .envApi
  echo "#TerrakubeRedisUsername=default" >> .envApi
  echo "TerrakubeRedisPassword=password123456" >> .envApi
  echo "#TERRAKUBE_ADMIN_GROUP=$TERRAKUBE_ADMIN_GROUP" >> .envApi
}

function generateExecutorVars(){
  USER=$(whoami)
  if [ "$USER" = "gitpod" ]; then
    AzBuilderApiUrl=$(gp url 8080)
    TerrakubeRegistryDomain=$(gp url 8075 | sed "s+https://++g")
    TerrakubeApiUrl=$(gp url 8080)
  else
    AzBuilderApiUrl="http://localhost:8080"
    TerrakubeRegistryDomain="http://localhost:8075"
    TerrakubeApiUrl="htp://localhost:8080"
  fi

  TerrakubeEnableSecurity=true
  InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
  
  TerraformStateType="AzureTerraformStateImpl"
  TerraformOutputType="AzureTerraformOutputImpl"
  
  ExecutorFlagBatch=false
  ExecutorFlagDisableAcknowledge=false
  TerrakubeToolsRepository=https://github.com/AzBuilder/terrakube-extensions.git
  TerrakubeToolsBranch=main
  
  JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m"

  rm -f  .envExecutor

  echo "TerrakubeEnableSecurity=$TerrakubeEnableSecurity" >> .envExecutor
  echo "InternalSecret=$InternalSecret" >> .envExecutor
  
  echo "TerraformStateType=$TerraformStateType" >> .envExecutor
  echo "AzureTerraformStateResourceGroup=$AZURE_STORAGE_RG" >> .envExecutor
  echo "AzureTerraformStateStorageAccountName=$AZURE_STORAGE_NAME" >> .envExecutor
  echo "AzureTerraformStateStorageContainerName=tfstate" >> .envExecutor
  echo "AzureTerraformStateStorageAccessKey=$AZURE_STORAGE_KEY" >> .envExecutor

  echo "TerraformOutputType=$TerraformOutputType" >> .envExecutor
  echo "AzureTerraformOutputAccountName=$AZURE_STORAGE_NAME" >> .envExecutor
  echo "AzureTerraformOutputAccountKey=$AZURE_STORAGE_KEY" >> .envExecutor

  echo "AzBuilderApiUrl=$AzBuilderApiUrl" >> .envExecutor
  echo "ExecutorFlagBatch=$ExecutorFlagBatch" >> .envExecutor
  echo "ExecutorFlagDisableAcknowledge=$ExecutorFlagDisableAcknowledge" >> .envExecutor
  echo "TerrakubeToolsRepository=$TerrakubeToolsRepository" >> .envExecutor
  echo "TerrakubeToolsBranch=$TerrakubeToolsBranch" >> .envExecutor
  echo "TerrakubeRegistryDomain=$TerrakubeRegistryDomain" >> .envExecutor
  echo "TerrakubeApiUrl=$TerrakubeApiUrl" >> .envExecutor
  echo "CustomTerraformReleasesUrl=\"https://releases.hashicorp.com/terraform/index.json\"" >> .envExecutor
  echo "CustomTofuReleasesUrl=\"https://api.github.com/repos/opentofu/opentofu/releases\"" >> .envExecutor
  echo "TerrakubeRedisHostname=localhost" >> .envExecutor
  echo "TerrakubeRedisPort=6379" >> .envExecutor
  echo "TerrakubeRedisSSL=false" >> .envExecutor
  echo "TerrakubeRedisUsername=default" >> .envExecutor
  echo "TerrakubeRedisPassword=password123456" >> .envExecutor
  echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS" >> .envExecutor
}

function generateRegistryVars(){
  USER=$(whoami)
  if [ "$USER" = "gitpod" ]; then
    AzBuilderRegistry=$(gp url 8075)
    AzBuilderApiUrl=$(gp url 8080)
    DexIssuerUri="$(gp url 5556)/dex"
    TerrakubeUiURL=$(gp url 3000)
    AppIssuerUri="$(gp url 5556)/dex"
  else
    AzBuilderRegistry="http://localhost:8075"
    AzBuilderApiUrl="http://localhost:8080"
    DexIssuerUri="http://localhost:5556/dex"
    TerrakubeUiURL="http://localhost:3000"
    AppIssuerUri="http://localhost:5556/dex"
  fi

  AuthenticationValidationTypeRegistry=DEX
  TerrakubeEnableSecurity=true
  PatSecret=ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=
  InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
  RegistryStorageType=AzureStorageImpl

  AppClientId=example-app

  JAVA_TOOL_OPTIONS="-Xmx256m -Xms128m"

  rm -f .envRegistry

  echo "AzBuilderRegistry=$AzBuilderRegistry" >> .envRegistry
  echo "AzBuilderApiUrl=$AzBuilderApiUrl" >> .envRegistry
  echo "AuthenticationValidationTypeRegistry=$AuthenticationValidationTypeRegistry" >> .envRegistry
  echo "TerrakubeEnableSecurity=$TerrakubeEnableSecurity" >> .envRegistry
  echo "DexIssuerUri=$DexIssuerUri" >> .envRegistry
  echo "TerrakubeUiURL=$TerrakubeUiURL" >> .envRegistry
  echo "PatSecret=$PatSecret" >> .envRegistry
  echo "InternalSecret=$InternalSecret" >> .envRegistry

  echo "RegistryStorageType=$RegistryStorageType" >> .envRegistry
  echo "AzureAccountName=$AZURE_STORAGE_NAME" >> .envRegistry
  echo "AzureAccountKey=$AZURE_STORAGE_KEY" >> .envRegistry

  echo "AppClientId=$AppClientId" >> .envRegistry
  echo "AppIssuerUri=$AppIssuerUri" >> .envRegistry
  echo "TerrakubeRedisHostname=localhost" >> .envRegistry
  echo "TerrakubeRedisPort=6379" >> .envRegistry
  echo "TerrakubeRedisSSL=false" >> .envRegistry
  echo "TerrakubeRedisUsername=default" >> .envRegistry
  echo "TerrakubeRedisPassword=password123456" >> .envRegistry
  echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS" >> .envRegistry
}

function generateUiVars(){
  USER=$(whoami)
  if [ "$USER" = "gitpod" ]; then
    REACT_CONFIG_TERRAKUBE_URL="$(gp url 8080)/api/v1/" 
    REACT_CONFIG_REDIRECT=$(gp url 3000)
    REACT_CONFIG_REGISTRY_URI=$(gp url 8075)  
    REACT_CONFIG_AUTHORITY="$(gp url 5556)/dex"
  else
    REACT_CONFIG_TERRAKUBE_URL="http://localhost:8080/api/v1/"
    REACT_CONFIG_REDIRECT="http://localhost:3000"
    REACT_CONFIG_REGISTRY_URI="http://localhost:8075"
    REACT_CONFIG_AUTHORITY="http://localhost:5556/dex"
  fi

  REACT_CONFIG_CLIENT_ID="example-app"
  REACT_CONFIG_SCOPE="email openid profile offline_access groups"
  REACT_APP_TERRAKUBE_VERSION=v$(git describe --tags --abbrev=0)

  rm -f .envUi

  echo "REACT_APP_TERRAKUBE_API_URL=$REACT_CONFIG_TERRAKUBE_URL" >> .envUi;
  echo "REACT_APP_CLIENT_ID=$REACT_CONFIG_CLIENT_ID" >> .envUi;
  echo "REACT_APP_AUTHORITY=$REACT_CONFIG_AUTHORITY" >> .envUi;
  echo "REACT_APP_REDIRECT_URI=$REACT_CONFIG_REDIRECT" >>.envUi;
  echo "REACT_APP_REGISTRY_URI=$REACT_CONFIG_REGISTRY_URI" >>.envUi;
  echo "REACT_APP_SCOPE"=$REACT_CONFIG_SCOPE >>.envUi
  echo "REACT_APP_TERRAKUBE_VERSION"=$REACT_APP_TERRAKUBE_VERSION >>.envUi

  generateUiConfigFile
}

function generateUiConfigFile(){
  # Recreate config file
  rm -f ui/env-config.js
  touch ui/env-config.js

  # Add assignment
  echo "window._env_ = {" >> ui/env-config.js

  # Read each line in .env file
  # Each line represents key=value pairs
  while read -r line || [[ -n "$line" ]];
  do
    # Split env variables by character `=`
    if printf '%s\n' "$line" | grep -q -e '='; then
      varname=$(printf '%s\n' "$line" | sed -e 's/=.*//')
      varvalue=$(printf '%s\n' "$line" | sed -e 's/^[^=]*=//')
    fi

    # Read value of current variable if exists as Environment variable
    value=$(printf '%s\n' "${!varname}")
    # Otherwise use value from .env file
    [[ -z $value ]] && value=${varvalue}

    # Append configuration property to JS file
    echo "  $varname: \"$value\"," >> ui/env-config.js
  done < .envUi

  echo "}" >> ui/env-config.js

  cp ui/env-config.js ui/public/ 

}

function generateDexConfiguration(){
  cp scripts/template/dex/template-config-ldap.yaml scripts/setup/dex/config-ldap.yaml
  
  USER=$(whoami)
  if [ "$USER" = "gitpod" ]; then
    jwtIssuer=$(gp url 5556)
    uiRedirect=$(gp url 3000)  
  else
    jwtIssuer="http://localhost:5556"
    uiRedirect="http://locahost:3000"
  fi
  
  sed -i "s+TEMPLATE_GITPOD_JWT_ISSUER+$jwtIssuer+gi" scripts/setup/dex/config-ldap.yaml
  sed -i "s+TEMPLATE_GITPOD_REDIRECT+$uiRedirect+gi" scripts/setup/dex/config-ldap.yaml

}

function generateThunderClientConfiguration(){
  USER=$(whoami)
  if [ "$USER" = "gitpod" ]; then
    jwtIssuer="$(gp url 5556)/dex"
    terrakubeApi=$(gp url 8080)
    terrakubeRegistry=$(gp url 8075)
  else
    jwtIssuer="http://localhost:5556"
    terrakubeApi="http://localhost:8080"
    terrakubeRegistry="http://localhost:8075"
  fi

  rm -f thunder-tests/thunderEnvironment.json
  cp scripts/template/thunder-tests/thunderEnvironment.json thunder-tests/
  sed -i "s+TEMPLATE_GITPOD_JWT_ISSUER+$jwtIssuer+gi" thunder-tests/thunderEnvironment.json
  sed -i "s+TEMPLATE_GITPOD_API+$terrakubeApi+gi" thunder-tests/thunderEnvironment.json
  sed -i "s+TEMPLATE_GITPOD_REGISTRY+$terrakubeRegistry+gi" thunder-tests/thunderEnvironment.json
}

function generateWorkspaceInformation(){
  rm -f GITPO.md
  cp scripts/template/gitpod/GITPOD_TEMPLATE.md GITPOD.md

  WORKSPACE_API=$(gp url 8080)
  WORKSPACE_REGISTRY=$(gp url 8075)
  WORKSPACE_EXECUTOR=$(gp url 8090)
  WORKSPACE_UI=$(gp url 3000)
  WORKSPACE_DEX=$(gp url 5556)
  WORKSPACE_MINIO=$(gp url 9000)
  WORKSPACE_CONSOLE_MINIO=$(gp url 9001)
  WORKSPACE_LOGIN_REGISTRY=$(gp url 8075 | sed "s+https://++g")

  sed -i "s+GITPOD_WORKSPACE_UI+$WORKSPACE_UI+gi" GITPOD.md
  sed -i "s+GITPOD_WORKSPACE_API+$WORKSPACE_API+gi" GITPOD.md
  sed -i "s+GITPOD_WORKSPACE_REGISTRY+$WORKSPACE_REGISTRY+gi" GITPOD.md
  sed -i "s+GITPOD_WORKSPACE_EXECUTOR+$WORKSPACE_EXECUTOR+gi" GITPOD.md
  sed -i "s+GITPOD_WORKSPACE_DEX+$WORKSPACE_DEX+gi" GITPOD.md
  sed -i "s+GITPOD_LOGIN_REGISTRY+$WORKSPACE_LOGIN_REGISTRY+gi" GITPOD.md
  sed -i "s+GITPOD_WORKSPACE_MINIO+$WORKSPACE_MINIO+gi" GITPOD.md
  sed -i "s+GITPOD_WORKSPACE_CONSOLE_MINIO+$WORKSPACE_CONSOLE_MINIO+gi" GITPOD.md
}

source .envAzure

generateApiVars
generateRegistryVars
generateExecutorVars
generateUiVars
generateDexConfiguration
generateThunderClientConfiguration

USER=$(whoami)
if [ "$USER" = "gitpod" ]; then
  generateWorkspaceInformation
fi

echo "Setup Development Environment Azure Completed"