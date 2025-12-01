#!/bin/bash

function generateApiVars(){
  CHE_API=$(echo "$DEVWORKSPACE_ID"-1)
  CHE_REGISTRY=$(echo "$DEVWORKSPACE_ID"-2) 
  CHE_EXECUTOR=$(echo "$DEVWORKSPACE_ID"-3)
  CHE_UI=$(echo "$DEVWORKSPACE_ID"-4)
  CHE_DEX=$(echo "$DEVWORKSPACE_ID"-5)
  DOMAIN=$(echo $CHE_DASHBOARD_URL | sed "s+https://++g")

  #TerrakubeHostname="http://localhost:8080"
  #AzBuilderExecutorUrl="http://localhost:8090/api/v1/terraform-rs"
  #DexIssuerUri="http://localhost:5556/dex"
  #TerrakubeUiURL="http://localhost:3000"

  TerrakubeHostname=$(echo "http://$CHE_API.$DOMAIN")
  AzBuilderExecutorUrl=$(echo "http://localhost:8090/api/v1/terraform-rs")
  DexIssuerUri="http://$CHE_DEX.$DOMAIN/dex"
  TerrakubeUiURL=$(echo "http://$CHE_UI.$DOMAIN")

  ApiDataSourceType="H2"
  GroupValidationType="DEX"
  UserValidationType="DEX"
  AuthenticationValidationType="DEX"
  PatSecret=ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=
  InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
  TERRAKUBE_ADMIN_GROUP="CUSTOM_ADMIN_NAME"

  StorageType="LOCAL"
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
  echo "TerrakubeUiURL=$TerrakubeUiURL" >> .envApi
  echo "spring_profiles_active=demo" >> .envApi
  echo "DexClientId=$DexClientId" >> .envApi
  echo "TerrakubeToolsRepository=$TerrakubeToolsRepository" >> .envApi
  echo "TerrakubeToolsBranch=$TerrakubeToolsBranch" >> .envApi
  echo "CustomTerraformReleasesUrl=\"https://releases.hashicorp.com/terraform/index.json\"" >> .envApi
  echo "CustomTofuReleasesUrl=\"https://api.github.com/repos/opentofu/opentofu/releases\"" >> .envApi
  echo "#TERRAKUBE_ADMIN_GROUP=$TERRAKUBE_ADMIN_GROUP" >> .envApi
}

function generateRegistryVars(){
  CHE_API=$(echo "$DEVWORKSPACE_ID"-1)
  CHE_REGISTRY=$(echo "$DEVWORKSPACE_ID"-2) 
  CHE_EXECUTOR=$(echo "$DEVWORKSPACE_ID"-3)
  CHE_DEX=$(echo "$DEVWORKSPACE_ID"-5)
  CHE_UI=$(echo "$DEVWORKSPACE_ID"-4)
  DOMAIN=$(echo $CHE_DASHBOARD_URL | sed "s+https://++g")
  USER=$(whoami)

  #AzBuilderApiUrl="http://localhost:8080"
  #TerrakubeRegistryDomain="http://localhost:8075"
  #TerrakubeApiUrl="htp://localhost:8080"

  AzBuilderApiUrl=$(echo "http://$CHE_API.$DOMAIN")
  TerrakubeRegistryDomain=$(echo "$CHE_REGISTRY.$DOMAIN")
  TerrakubeApiUrl=$(echo "http://$CHE_API.$DOMAIN")

  TerrakubeEnableSecurity=true
  InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
  TerraformStateType=LocalTerraformStateImpl
  TerraformOutputType=LocalTerraformOutputImpl
  ExecutorFlagBatch=false
  ExecutorFlagDisableAcknowledge=false
  TerrakubeToolsRepository=https://github.com/AzBuilder/terrakube-extensions.git
  TerrakubeToolsBranch=main
  
  JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m"

  rm -f  .envExecutor

  echo "TerrakubeEnableSecurity=$TerrakubeEnableSecurity" >> .envExecutor
  echo "InternalSecret=$InternalSecret" >> .envExecutor
  echo "TerraformStateType=$TerraformStateType" >> .envExecutor
  echo "TerraformOutputType=$TerraformOutputType" >> .envExecutor
  echo "AzBuilderApiUrl=$AzBuilderApiUrl" >> .envExecutor
  echo "ExecutorFlagBatch=$ExecutorFlagBatch" >> .envExecutor
  echo "ExecutorFlagDisableAcknowledge=$ExecutorFlagDisableAcknowledge" >> .envExecutor
  echo "TerrakubeToolsRepository=$TerrakubeToolsRepository" >> .envExecutor
  echo "TerrakubeToolsBranch=$TerrakubeToolsBranch" >> .envExecutor
  echo "TerrakubeRegistryDomain=$TerrakubeRegistryDomain" >> .envExecutor
  echo "TerrakubeApiUrl=$TerrakubeApiUrl" >> .envExecutor
  echo "CustomTerraformReleasesUrl=\"https://releases.hashicorp.com/terraform/index.json\"" >> .envExecutor
  echo "CustomTofuReleasesUrl=\"https://api.github.com/repos/opentofu/opentofu/releases\"" >> .envExecutor
  echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS" >> .envExecutor
}

function generateExecutorVars(){
  CHE_API=$(echo "$DEVWORKSPACE_ID"-1)
  CHE_REGISTRY=$(echo "$DEVWORKSPACE_ID"-2) 
  CHE_EXECUTOR=$(echo "$DEVWORKSPACE_ID"-3)
  CHE_DEX=$(echo "$DEVWORKSPACE_ID"-5)
  CHE_UI=$(echo "$DEVWORKSPACE_ID"-4)
  DOMAIN=$(echo $CHE_DASHBOARD_URL | sed "s+https://++g")

  #AzBuilderRegistry="http://localhost:8075"
  #AzBuilderApiUrl="http://localhost:8080"
  #DexIssuerUri="http://localhost:5556/dex"
  #TerrakubeUiURL="http://localhost:3000"
  #AppIssuerUri="http://localhost:5556/dex"

  AzBuilderRegistry=$(echo "http://$CHE_REGISTRY.$DOMAIN")
  AzBuilderApiUrl=$(echo "http://$CHE_API.$DOMAIN")
  DexIssuerUri="http://$CHE_DEX.$DOMAIN/dex"
  TerrakubeUiURL=$(echo "http://$CHE_UI.$DOMAIN")
  AppIssuerUri="http://$CHE_DEX.$DOMAIN/dex"
  


  AuthenticationValidationTypeRegistry=DEX
  TerrakubeEnableSecurity=true
  PatSecret=ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=
  InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
  RegistryStorageType=Local
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
  echo "AppClientId=$AppClientId" >> .envRegistry
  echo "AppIssuerUri=$AppIssuerUri" >> .envRegistry
  echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS" >> .envRegistry
}

function generateUiVars(){
  CHE_API=$(echo "$DEVWORKSPACE_ID"-1)
  CHE_REGISTRY=$(echo "$DEVWORKSPACE_ID"-2) 
  CHE_EXECUTOR=$(echo "$DEVWORKSPACE_ID"-3)
  CHE_UI=$(echo "$DEVWORKSPACE_ID"-4)
  CHE_DEX=$(echo "$DEVWORKSPACE_ID"-5)
  DOMAIN=$(echo $CHE_DASHBOARD_URL | sed "s+https://++g")

  #REACT_CONFIG_TERRAKUBE_URL="http://localhost:8080/api/v1/"
  #REACT_CONFIG_REDIRECT="http://localhost:3000"
  #REACT_CONFIG_REGISTRY_URI="http://localhost:8075"
  #REACT_CONFIG_AUTHORITY="http://localhost:5556/dex"

  REACT_CONFIG_TERRAKUBE_URL=$(echo "http://$CHE_API.$DOMAIN"/api/v1/)
  REACT_CONFIG_REDIRECT=$(echo "http://$CHE_UI.$DOMAIN")
  REACT_CONFIG_REGISTRY_URI=$(echo "http://$CHE_REGISTRY.$DOMAIN")
  REACT_CONFIG_AUTHORITY="http://$CHE_DEX.$DOMAIN/dex"

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
  CHE_DEX=$(echo "$DEVWORKSPACE_ID"-5)
  CHE_UI=$(echo "$DEVWORKSPACE_ID"-4)
  DOMAIN=$(echo $CHE_DASHBOARD_URL | sed "s+https://++g")

  cp scripts/template/dex/template-config-ldap.yaml scripts/setup/dex/config-ldap.yaml
  jwtIssuer=$(echo "http://$CHE_DEX.$DOMAIN")
  sed -i "s+TEMPLATE_GITPOD_JWT_ISSUER+$jwtIssuer+gi" scripts/setup/dex/config-ldap.yaml
  uiRedirect=$(echo "http://$CHE_UI.$DOMAIN")
  sed -i "s+TEMPLATE_GITPOD_REDIRECT+$uiRedirect+gi" scripts/setup/dex/config-ldap.yaml
  sed -i "s+ldap-service+localhost+gi" scripts/setup/dex/config-ldap.yaml
}

function generateThunderClientConfiguration(){
  CHE_API=$(echo "$DEVWORKSPACE_ID"-1)
  CHE_REGISTRY=$(echo "$DEVWORKSPACE_ID"-2) 
  CHE_EXECUTOR=$(echo "$DEVWORKSPACE_ID"-3)
  CHE_UI=$(echo "$DEVWORKSPACE_ID"-4)
  CHE_DEX=$(echo "$DEVWORKSPACE_ID"-5)
  DOMAIN=$(echo $CHE_DASHBOARD_URL | sed "s+https://++g")

  jwtIssuer=$(echo "http://$CHE_DEX.$DOMAIN/dex")
  terrakubeApi=$(echo "http://$CHE_API.$DOMAIN")
  terrakubeRegistry=$(echo "http://$CHE_REGISTRY.$DOMAIN")

  rm -f thunder-tests/thunderEnvironment.json
  cp scripts/template/thunder-tests/thunderEnvironment.json thunder-tests/
  sed -i "s+TEMPLATE_GITPOD_JWT_ISSUER+$jwtIssuer+gi" thunder-tests/thunderEnvironment.json
  sed -i "s+TEMPLATE_GITPOD_API+$terrakubeApi+gi" thunder-tests/thunderEnvironment.json
  sed -i "s+TEMPLATE_GITPOD_REGISTRY+$terrakubeRegistry+gi" thunder-tests/thunderEnvironment.json
}

function generateWorkspaceInformation(){
  CHE_API=$(echo "$DEVWORKSPACE_ID"-1)
  CHE_REGISTRY=$(echo "$DEVWORKSPACE_ID"-2) 
  CHE_EXECUTOR=$(echo "$DEVWORKSPACE_ID"-3)
  CHE_UI=$(echo "$DEVWORKSPACE_ID"-4)
  CHE_DEX=$(echo "$DEVWORKSPACE_ID"-5)
  DOMAIN=$(echo $CHE_DASHBOARD_URL | sed "s+https://++g")

  rm -f CHE.md
  cp scripts/template/gitpod/GITPOD_TEMPLATE.md CHE.md

  WORKSPACE_API=$(echo "http://$CHE_API.$DOMAIN")
  WORKSPACE_REGISTRY=$(echo "http://$CHE_REGISTRY.$DOMAIN")
  WORKSPACE_EXECUTOR=$(echo "http://$CHE_EXECUTOR.$DOMAIN")
  WORKSPACE_UI=$(echo "http://$CHE_UI.$DOMAIN")
  WORKSPACE_DEX=$(echo "http://$CHE_DEX.$DOMAIN/dex/.well-known/openid-configuration")
  #WORKSPACE_MINIO=$(gp url 9000)
  #WORKSPACE_CONSOLE_MINIO=$(gp url 9001)
  #WORKSPACE_LOGIN_REGISTRY=$(gp url 8075 | sed "s+https://++g")

  sed -i "s+GITPOD_WORKSPACE_UI+$WORKSPACE_UI+gi" CHE.md
  sed -i "s+GITPOD_WORKSPACE_API+$WORKSPACE_API+gi" CHE.md
  sed -i "s+GITPOD_WORKSPACE_REGISTRY+$WORKSPACE_REGISTRY+gi" CHE.md
  sed -i "s+GITPOD_WORKSPACE_EXECUTOR+$WORKSPACE_EXECUTOR+gi" CHE.md
  sed -i "s+GITPOD_WORKSPACE_DEX+$WORKSPACE_DEX+gi" CHE.md
  #sed -i "s+GITPOD_LOGIN_REGISTRY+$WORKSPACE_LOGIN_REGISTRY+gi" GITPOD.md
  #sed -i "s+GITPOD_WORKSPACE_MINIO+$WORKSPACE_MINIO+gi" GITPOD.md
  #sed -i "s+GITPOD_WORKSPACE_CONSOLE_MINIO+$WORKSPACE_CONSOLE_MINIO+gi" GITPOD.md
}

cd /projects/terrakube/

generateApiVars
generateRegistryVars
generateExecutorVars
generateUiVars
generateDexConfiguration
generateThunderClientConfiguration
generateWorkspaceInformation

cp ./scripts/template/azure/.envAzureSample .envAzure
cp ./scripts/template/google/.envGcpSample .envGcp

echo "Setup Development Environment Che Completed"

sleep infinity