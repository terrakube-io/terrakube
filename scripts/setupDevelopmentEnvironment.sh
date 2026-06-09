#!/bin/bash

while getopts 'd:s:' OPTION; do
	echo $OPTION
	case "$OPTION" in
	d)
		database_value="$OPTARG"
		echo "Using database $OPTARG"
		;;
	s)
		storage_value="$OPTARG"
		echo "Using storage $OPTARG"
		;;
	?)
		echo "script usage: $(basename \$0) [-s storage] [-d database]" >&2
		exit 1
		;;
	esac
done

function generateApiVars() {
	USER=$(whoami)
	if [ "$CODESPACES" = "true" ]; then
		TerrakubeHostname=$(echo "https://$CODESPACE_NAME-8080.app.github.dev" | sed "s+https://++g")
		DexIssuerUri="https://$CODESPACE_NAME-5556.app.github.dev/dex"
		TerrakubeUiURL="https://$CODESPACE_NAME-3000.app.github.dev"
	elif [ "$USER" = "vscode" ]; then
		TerrakubeHostname="terrakube-api.platform.local"
		AzBuilderExecutorUrl="http://localhost:8090/api/v1/terraform-rs"
		DexIssuerUri="https://terrakube-dex.platform.local/dex"
		TerrakubeUiURL="https://terrakube.platform.local"
	else
		TerrakubeHostname="localhost:8080"
		AzBuilderExecutorUrl="http://localhost:8090/api/v1/terraform-rs"
		DexIssuerUri="http://localhost:5556/dex"
		TerrakubeUiURL="http://localhost:3000"
	fi

  TerrakubeRedisHostname=terrakube-redis
	GroupValidationType="DEX"
	UserValidationType="DEX"
	AuthenticationValidationType="DEX"
	PatSecret=ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=
	InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
	TERRAKUBE_ADMIN_GROUP="CUSTOM_ADMIN_NAME"

	DexClientId="example-app"

	TerrakubeToolsRepository=https://github.com/terrakube-io/terrakube-extensions.git
	TerrakubeToolsBranch=main

	JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m"

	rm -f .envApi

	if [ "$database_value" = "POSTGRESQL" ]; then
		ApiDataSourceType="POSTGRESQL"
		DatasourceDatabase="terrakubedb"
		DatasourceUser="terrakube"
		DatasourceSchema="public"
		DatasourceHostname="postgresql-service"
		DatasourcePassword="terrakubepassword"
	else
		ApiDataSourceType="H2"
	fi

	if [ "$storage_value" = "MINIO" ]; then
		StorageType="AWS"
		AwsStorageAccessKey="minioadmin"
		AwsStorageSecretKey="minioadmin"
		AwsStorageBucketName="sample"
		AwsStorageRegion="us-east-1"
		AwsEndpoint="http://minio:9000"
	else
		StorageType="LOCAL"
	fi

	echo "ApiDataSourceType=$ApiDataSourceType" >>.envApi
	echo "DatasourceHostname=$DatasourceHostname" >>.envApi
	echo "DatasourceDatabase=$DatasourceDatabase" >>.envApi
	echo "DatasourceTrustCertificate=true" >>.envApi
	echo "DatasourceUser=$DatasourceUser" >>.envApi
	echo "DatasourcePassword=$DatasourcePassword" >>.envApi
	echo "DatasourceSchema=$DatasourceSchema" >>.envApi

	echo "StorageType=$StorageType" >>.envApi
	echo "AwsStorageAccessKey=$AwsStorageAccessKey" >>.envApi
	echo "AwsStorageSecretKey=$AwsStorageSecretKey" >>.envApi
	echo "AwsStorageBucketName=$AwsStorageBucketName" >>.envApi
	echo "AwsStorageRegion=$AwsStorageRegion" >>.envApi
	echo "AwsEndpoint=$AwsEndpoint" >>.envApi

	echo "AzureAccountName=$AzureAccountName" >>.envApi
	echo "AzureAccountKey=$AzureAccountKey" >>.envApi
	echo "AzureConnectionString=$AzureConnectionString" >>.envApi
	echo "AzureCustomConnectionString=true" >>.envApi

	echo "GroupValidationType=$GroupValidationType" >>.envApi
	echo "UserValidationType=$UserValidationType" >>.envApi
	echo "AuthenticationValidationType=$AuthenticationValidationType" >>.envApi
	echo "TerrakubeHostname=$TerrakubeHostname" >>.envApi
	echo "AzBuilderExecutorUrl=$AzBuilderExecutorUrl" >>.envApi
	echo "PatSecret=$PatSecret" >>.envApi
	echo "InternalSecret=$InternalSecret" >>.envApi
	echo "DexIssuerUri=$DexIssuerUri" >>.envApi
	echo "TerrakubeUiURL=$TerrakubeUiURL" >>.envApi
	echo "spring_profiles_active=demo" >>.envApi
	echo "DexClientId=$DexClientId" >>.envApi
	echo "TerrakubeToolsRepository=$TerrakubeToolsRepository" >>.envApi
	echo "TerrakubeToolsBranch=$TerrakubeToolsBranch" >>.envApi
	echo "CustomTerraformReleasesUrl=\"https://releases.hashicorp.com/terraform/index.json\"" >>.envApi
	echo "CustomTofuReleasesUrl=\"https://api.github.com/repos/opentofu/opentofu/releases\"" >>.envApi
	echo "TerrakubeRedisHostname=$TerrakubeRedisHostname" >>.envApi
	echo "TerrakubeRedisPort=6379" >>.envApi
	echo "TerrakubeRedisSSL=false" >>.envApi
	echo "#TerrakubeRedisUsername=default" >>.envApi
	echo "DynamicCredentialPublicKeyPath=/workspaces/terrakube/public.pem" >>.envApi
	echo "DynamicCredentialPrivateKeyPath=/workspaces/terrakube/private.pem" >>.envApi
	echo "TerrakubeRedisPassword=password123456" >>.envApi
	echo "#TERRAKUBE_ADMIN_GROUP=$TERRAKUBE_ADMIN_GROUP" >>.envApi
}

function generateExecutorVars() {
	USER=$(whoami)
	if [ "$CODESPACES" = "true" ]; then
  	AzBuilderApiUrl="https://$CODESPACE_NAME-8080.app.github.dev"
    TerrakubeRegistryDomain=$(echo "https://$CODESPACE_NAME-8075.app.github.dev" | sed "s+https://++g")
    TerrakubeApiUrl="https://$CODESPACE_NAME-8080.app.github.dev"
  elif [ "$USER" = "vscode" ]; then
		AzBuilderApiUrl="https://terrakube-api.platform.local"
		TerrakubeRegistryDomain="terrakube-registry.platform.local"
		TerrakubeApiUrl="https://terrakube-api.platform.local"
	else
		AzBuilderApiUrl="http://localhost:8080"
		TerrakubeRegistryDomain="localhost:8075"
		TerrakubeApiUrl="http://localhost:8080"
	fi

	if [ "$storage_value" = "MINIO" ]; then
		TerraformStateType=AwsTerraformStateImpl
		AwsTerraformStateAccessKey="minioadmin"
		AwsTerraformStateSecretKey="minioadmin"
		AwsTerraformStateBucketName="sample"
		AwsTerraformStateRegion="us-east-1"

		TerraformOutputType=AwsTerraformOutputImpl
		AwsTerraformOutputAccessKey="minioadmin"
		AwsTerraformOutputSecretKey="minioadmin"
		AwsTerraformOutputBucketName="sample"
		AwsTerraformOutputRegion="us-east-1"
		AwsEndpoint="http://minio:9000"
  else
		TerraformStateType=LocalTerraformStateImpl
		TerraformOutputType=LocalTerraformOutputImpl
	fi

  TerrakubeRedisHostname=terrakube-redis
	TerrakubeEnableSecurity=true
	InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=

	ExecutorFlagBatch=false
	ExecutorFlagDisableAcknowledge=false
	TerrakubeToolsRepository=https://github.com/terrakube-io/terrakube-extensions.git
	TerrakubeToolsBranch=main

	# Use unique JMX port for executor (45557) to avoid conflicts with other Java apps
	JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m -Dcom.sun.management.jmxremote.port=45557 -Dcom.sun.management.jmxremote.rmi.port=45557 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"

	rm -f .envExecutor

	echo "TerrakubeEnableSecurity=$TerrakubeEnableSecurity" >>.envExecutor
	echo "InternalSecret=$InternalSecret" >>.envExecutor

	echo "TerraformStateType=$TerraformStateType" >>.envExecutor
	echo "AwsTerraformStateAccessKey=$AwsTerraformStateAccessKey" >>.envExecutor
	echo "AwsTerraformStateSecretKey=$AwsTerraformStateSecretKey" >>.envExecutor
	echo "AwsTerraformStateBucketName=$AwsTerraformStateBucketName" >>.envExecutor
	echo "AwsTerraformStateRegion=$AwsTerraformStateRegion" >>.envExecutor
	echo "AwsEndpoint=$AwsEndpoint" >>.envExecutor

	echo "TerraformOutputType=$TerraformOutputType" >>.envExecutor
	echo "AwsTerraformOutputAccessKey=$AwsTerraformOutputAccessKey" >>.envExecutor
	echo "AwsTerraformOutputSecretKey=$AwsTerraformOutputSecretKey" >>.envExecutor
	echo "AwsTerraformOutputBucketName=$AwsTerraformOutputBucketName" >>.envExecutor
	echo "AwsTerraformOutputRegion=$AwsTerraformOutputRegion" >>.envExecutor

	echo "AzureAccountName=$AzureAccountName" >>.envExecutor
	echo "AzureAccountKey=$AzureAccountKey" >>.envExecutor
	echo "AzureConnectionString=$AzureConnectionString" >>.envExecutor
	echo "AzureCustomConnectionString=true" >>.envExecutor
	echo "AzureUseStorageEndpoint=true" >>.envExecutor
	echo "AzureStorageEndpoint=http://azurite-service:10000/devstoreaccount1" >>.envExecutor
	echo "AzureTerraformStateResourceGroup=fake-rg" >>.envExecutor
	echo "AzureTerraformStateStorageAccountName=devstoreaccount1" >>.envExecutor
	echo "AzureTerraformStateStorageContainerName=tfstate" >>.envExecutor
	echo "AzureTerraformStateResourceGroup=fake-rg" >>.envExecutor
	echo "AzureTerraformStateStorageAccessKey=$AzureAccountKey" >>.envExecutor

	echo "AzBuilderApiUrl=$AzBuilderApiUrl" >>.envExecutor
	echo "ExecutorFlagBatch=$ExecutorFlagBatch" >>.envExecutor
	echo "ExecutorFlagDisableAcknowledge=$ExecutorFlagDisableAcknowledge" >>.envExecutor
	echo "TerrakubeToolsRepository=$TerrakubeToolsRepository" >>.envExecutor
	echo "TerrakubeToolsBranch=$TerrakubeToolsBranch" >>.envExecutor
	echo "TerrakubeRegistryDomain=$TerrakubeRegistryDomain" >>.envExecutor
	echo "TerrakubeApiUrl=$TerrakubeApiUrl" >>.envExecutor
	echo "CustomTerraformReleasesUrl=\"https://releases.hashicorp.com/terraform/index.json\"" >>.envExecutor
	echo "CustomTofuReleasesUrl=\"https://api.github.com/repos/opentofu/opentofu/releases\"" >>.envExecutor
	echo "TerrakubeRedisHostname=$TerrakubeRedisHostname" >>.envExecutor
	echo "TerrakubeRedisPort=6379" >>.envExecutor
	echo "TerrakubeRedisSSL=false" >>.envExecutor
	echo "TerrakubeRedisUsername=default" >>.envExecutor
	echo "TerrakubeRedisPassword=password123456" >>.envExecutor
	echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS" >>.envExecutor
}

function generateRegistryVars() {
	USER=$(whoami)
	if [ "$CODESPACES" = "true" ]; then
		AzBuilderRegistry="https://$CODESPACE_NAME-8075.app.github.dev"
		AzBuilderApiUrl="https://$CODESPACE_NAME-8080.app.github.dev"
		DexIssuerUri="https://$CODESPACE_NAME-5556.app.github.dev/dex"
		TerrakubeUiURL="https://$CODESPACE_NAME-3000.app.github.dev"
		AppIssuerUri="https://$CODESPACE_NAME-5556.app.github.dev/dex"
	elif [ "$USER" = "vscode" ]; then
		AzBuilderRegistry="https://terrakube-registry.platform.local"
		AzBuilderApiUrl="https://terrakube-api.platform.local"
		DexIssuerUri="https://terrakube-dex.platform.local/dex"
		TerrakubeUiURL="https://terrakube.platform.local"
		AppIssuerUri="https://terrakube-dex.platform.local/dex"
	else
		AzBuilderRegistry="http://localhost:8075"
		AzBuilderApiUrl="http://localhost:8080"
		DexIssuerUri="http://localhost:5556/dex"
		TerrakubeUiURL="http://locahost:3000"
		AppIssuerUri="https://localhost:5556/dex"
	fi

	if [ "$storage_value" = "MINIO" ]; then
		RegistryStorageType=AwsStorageImpl
		AwsStorageAccessKey="minioadmin"
		AwsStorageSecretKey="minioadmin"
		AwsStorageBucketName="sample"
		AwsStorageRegion="us-east-1"
		AwsEndpoint="http://terrakube-minio:9000"
  else
		RegistryStorageType=Local
	fi

	AuthenticationValidationTypeRegistry=DEX
	TerrakubeEnableSecurity=true
	PatSecret=ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=
	InternalSecret=S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM=
	AppClientId=example-app

	# Use unique JMX port for registry (45558) to avoid conflicts with other Java apps
	JAVA_TOOL_OPTIONS="-Xmx256m -Xms128m -Dcom.sun.management.jmxremote.port=45558 -Dcom.sun.management.jmxremote.rmi.port=45558 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"

	rm -f .envRegistry

	echo "AzBuilderRegistry=$AzBuilderRegistry" >>.envRegistry
	echo "AzBuilderApiUrl=$AzBuilderApiUrl" >>.envRegistry
	echo "AuthenticationValidationTypeRegistry=$AuthenticationValidationTypeRegistry" >>.envRegistry
	echo "TerrakubeEnableSecurity=$TerrakubeEnableSecurity" >>.envRegistry
	echo "DexIssuerUri=$DexIssuerUri" >>.envRegistry
	echo "TerrakubeUiURL=$TerrakubeUiURL" >>.envRegistry
	echo "PatSecret=$PatSecret" >>.envRegistry
	echo "InternalSecret=$InternalSecret" >>.envRegistry
	echo "RegistryStorageType=$RegistryStorageType" >>.envRegistry
	echo "AppClientId=$AppClientId" >>.envRegistry
	echo "AppIssuerUri=$AppIssuerUri" >>.envRegistry
	echo "AwsStorageAccessKey=$AwsStorageAccessKey" >>.envRegistry
	echo "AwsStorageSecretKey=$AwsStorageSecretKey" >>.envRegistry
	echo "AwsStorageBucketName=$AwsStorageBucketName" >>.envRegistry
	echo "AwsStorageRegion=$AwsStorageRegion" >>.envRegistry
	echo "AwsEndpoint=$AwsEndpoint" >>.envRegistry
	echo "AzureAccountName=$AzureAccountName" >>.envRegistry
	echo "AzureAccountKey=$AzureAccountKey" >>.envRegistry
	echo "AzureConnectionString=$AzureConnectionString" >>.envRegistry
	echo "AzureCustomConnectionString=true" >>.envRegistry
	echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS" >>.envRegistry
}

function generateUiVars() {
	USER=$(whoami)
	if [ "$CODESPACES" = "true" ]; then
		REACT_CONFIG_TERRAKUBE_URL="https://$CODESPACE_NAME-8080.app.github.dev/api/v1/"
		REACT_CONFIG_REDIRECT="https://$CODESPACE_NAME-3000.app.github.dev"
		REACT_CONFIG_REGISTRY_URI="https://$CODESPACE_NAME-8075.app.github.dev"
		REACT_CONFIG_AUTHORITY="https://$CODESPACE_NAME-5556.app.github.dev/dex"
	else
		REACT_CONFIG_TERRAKUBE_URL="https://terrakube-api.platform.local/api/v1/"
		REACT_CONFIG_REDIRECT="https://terrakube.platform.local"
		REACT_CONFIG_REGISTRY_URI="https://terrakube-registry.platform.local"
		REACT_CONFIG_AUTHORITY="https://terrakube-dex.platform.local/dex"
	fi

	REACT_CONFIG_CLIENT_ID="example-app"
	REACT_CONFIG_SCOPE="email openid profile offline_access groups"
	REACT_APP_TERRAKUBE_VERSION="devcontainer"

	rm -f .envUi

	echo "REACT_APP_TERRAKUBE_API_URL=$REACT_CONFIG_TERRAKUBE_URL" >>.envUi
	echo "REACT_APP_CLIENT_ID=$REACT_CONFIG_CLIENT_ID" >>.envUi
	echo "REACT_APP_AUTHORITY=$REACT_CONFIG_AUTHORITY" >>.envUi
	echo "REACT_APP_REDIRECT_URI=$REACT_CONFIG_REDIRECT" >>.envUi
	echo "REACT_APP_REGISTRY_URI=$REACT_CONFIG_REGISTRY_URI" >>.envUi
	echo "REACT_APP_SCOPE"=$REACT_CONFIG_SCOPE >>.envUi
	echo "REACT_APP_TERRAKUBE_SEND_COOKIES=false" >>.envUi
	echo "REACT_APP_TERRAKUBE_VERSION"=$REACT_APP_TERRAKUBE_VERSION >>.envUi
	REACT_CONFIG_REDIRECT=$(echo $REACT_CONFIG_REDIRECT | sed "s+https://++g")
	echo "__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS"=$REACT_CONFIG_REDIRECT >>.envUi

	generateUiConfigFile
}

function generateUiConfigFile() {
	# Recreate config file
	rm -f ui/env-config.js
	touch ui/env-config.js

	# Also create .env file in ui directory for env.sh script
	rm -f ui/.env
	cp .envUi ui/.env

	# Add assignment
	echo "window._env_ = {" >>ui/env-config.js

	# Read each line in .env file
	# Each line represents key=value pairs
	while read -r line || [[ -n "$line" ]]; do
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
		echo "  $varname: \"$value\"," >>ui/env-config.js
	done <.envUi

	echo "}" >>ui/env-config.js

	cp ui/env-config.js ui/public/

}

function generateDexConfiguration() {
	cp scripts/template/devcontainer/template-config-ldap.yaml scripts/setup/devcontainer/config-ldap.yaml

	USER=$(whoami)
	if [ "$CODESPACES" = "true" ]; then
		jwtIssuer="https://$CODESPACE_NAME-5556.app.github.dev"
		uiRedirect="https://$CODESPACE_NAME-3000.app.github.dev"
	elif [ "$USER" = "vscode" ]; then
	  echo "Echo using local devcontainer"
		jwtIssuer="https://terrakube-dex.platform.local"
		uiRedirect="https://terrakube.platform.local"
	fi

	sed -i "s+TEMPLATE_DEVCONTAINER_JWT_ISSUER+$jwtIssuer+gi" scripts/setup/devcontainer/config-ldap.yaml
	sed -i "s+TEMPLATE_DEVCONTAINER_REDIRECT+$uiRedirect+gi" scripts/setup/devcontainer/config-ldap.yaml
}

function generateWorkspaceInformation() {
	rm -f devcontainer.md
	cp scripts/template/DEVCONTAINER_TEMPLATE.md DEVCONTAINER.md

	WORKSPACE_API="https://$CODESPACE_NAME-8080.app.github.dev"
	WORKSPACE_REGISTRY="https://$CODESPACE_NAME-8075.app.github.dev"
	WORKSPACE_EXECUTOR="https://$CODESPACE_NAME-8090.app.github.dev"
	WORKSPACE_UI="https://$CODESPACE_NAME-3000.app.github.dev"
	WORKSPACE_DEX="https://$CODESPACE_NAME-5556.app.github.dev"
	WORKSPACE_MINIO="https://$CODESPACE_NAME-9000.app.github.dev"
	WORKSPACE_CONSOLE_MINIO="https://$CODESPACE_NAME-9001.app.github.dev"
	WORKSPACE_LOGIN_REGISTRY=$(echo "https://$CODESPACE_NAME-8075.app.github.dev" | sed "s+https://++g")

	sed -i "s+DEVCONTAINER_WORKSPACE_UI+$WORKSPACE_UI+gi" DEVCONTAINER.md
	sed -i "s+DEVCONTAINER_WORKSPACE_API+$WORKSPACE_API+gi" DEVCONTAINER.md
	sed -i "s+DEVCONTAINER_WORKSPACE_REGISTRY+$WORKSPACE_REGISTRY+gi" DEVCONTAINER.md
	sed -i "s+DEVCONTAINER_WORKSPACE_EXECUTOR+$WORKSPACE_EXECUTOR+gi" DEVCONTAINER.md
	sed -i "s+DEVCONTAINER_WORKSPACE_DEX+$WORKSPACE_DEX+gi" DEVCONTAINER.md
	sed -i "s+DEVCONTAINER_LOGIN_REGISTRY+$WORKSPACE_LOGIN_REGISTRY+gi" DEVCONTAINER.md
	sed -i "s+DEVCONTAINER_WORKSPACE_MINIO+$WORKSPACE_MINIO+gi" DEVCONTAINER.md
	sed -i "s+DEVCONTAINER_WORKSPACE_CONSOLE_MINIO+$WORKSPACE_CONSOLE_MINIO+gi" DEVCONTAINER.md
}

#if [ "$USER" != "gitpod" ] && [ "$USER" == "vscode" ]; then
#	openssl x509 -outform der -in /workspaces/terrakube/.devcontainer/rootCA.pem -out /workspaces/terrakube/.devcontainer/rootCA.der
#
#	if keytool -list -cacerts -storepass "changeit" | grep -q "custom-ca"; then
#		echo "Alias $ALIAS exists. Deleting it first..."
#		keytool -delete -alias "custom-ca" -cacerts -storepass "changeit" -noprompt
#	fi
#
#	keytool -import -alias custom-ca -cacerts -file /workspaces/terrakube/.devcontainer/rootCA.der -storepass "changeit" -noprompt
#
#	#  export AZURE_STORAGE_CONNECTION_STRING="DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://azurite-service:10000/devstoreaccount1;"
#	#
#	#  az storage container create --name registry
#	#  az storage container create --name tfstate
#	#  az storage container create --name tfoutput
#
#	SApassword=P@ssw0rd!
#
#	# Parameters
#	dacpath=$1
#
#	for i in {1..30}; do
#		sqlcmd -S mssql-service -U sa -P $SApassword -d master -C -Q "SELECT * FROM SYS.DATABASES" >/dev/null
#		if [ $? -eq 0 ]; then
#			sqlcmd -S mssql-service -U sa -P P@ssw0rd! -i /workspaces/terrakube/scripts/init-mssql.sql -C
#			echo "mssql ready"
#			break
#		else
#			echo "mssql not ready yet..."
#			sleep 1
#		fi
#	done
#fi

generateApiVars
generateRegistryVars
generateExecutorVars
generateUiVars
generateDexConfiguration

USER=$(whoami)
	if [ "$CODESPACES" = "true" ]; then
	generateWorkspaceInformation
fi

cp ./scripts/template/azure/.envAzureSample .envAzure
cp ./scripts/template/google/.envGcpSample .envGcp

openssl genrsa -out private_temp.pem 2048
openssl rsa -in private_temp.pem -outform PEM -pubout -out public.pem
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in private_temp.pem -out private.pem
rm private_temp.pem

echo "Setup Development Environment Completed"
