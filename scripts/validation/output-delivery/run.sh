#!/usr/bin/env bash
# Starts the API and the executor as plain jars (H2, local storage, Redis), runs one scenario against
# them and stops them again. No images, no ingress, no identity provider.
#
# Needs: java 25 on the PATH, python3, git, bash, and a Redis on $REDIS_PORT (password $REDIS_PASSWORD).
# Usage: API_JAR=api/target/api-server-*.jar EXECUTOR_JAR=executor/target/executor-*.jar run.sh kill-mid-step
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCENARIO="${1:-kill-mid-step}"
API_JAR="$(ls ${API_JAR:?set API_JAR} | head -1)"
EXECUTOR_JAR="$(ls ${EXECUTOR_JAR:?set EXECUTOR_JAR} | head -1)"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-password123456}"
API_PORT="${API_PORT:-18080}"
EXECUTOR_PORT="${EXECUTOR_PORT:-18090}"
WORK="${WORK_DIR:-$(mktemp -d)}"
# Throwaway values for this run only. They are not used anywhere else.
PAT_SECRET="ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g="
INTERNAL_SECRET="S2JeOGNNZXJQTlpWNmhTITkha2NEKkt1VVBVQmFeQjM="

mkdir -p "$WORK/home"
pids=()
cleanup() {
  status=$?
  for pid in "${pids[@]:-}"; do kill -9 "$pid" 2>/dev/null || true; done
  if [ "$status" -ne 0 ]; then
    echo "--- api log (tail) ---"; tail -n 60 "$WORK/api.log" 2>/dev/null || true
    echo "--- executor log (tail) ---"; tail -n 60 "$WORK/executor.log" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT

# Two local git repositories, so nothing is fetched from the network: the one the workspace clones, and the
# extensions repository that the API and the executor clone when they start.
make_repo() {
  git init -q -b main "$1"
  echo "$2" > "$1/$3"
  git -C "$1" add .
  git -C "$1" -c user.email=validation@example.com -c user.name=validation commit -qm init
}
make_repo "$WORK/repo" 'terraform {}' main.tf
make_repo "$WORK/tools" 'validation' README.md
TOOLS_URL="file://$WORK/tools"

env \
  SERVER_PORT="$API_PORT" ApiDataSourceType=H2 StorageType=LOCAL \
  GroupValidationType=DEX UserValidationType=DEX AuthenticationValidationType=DEX \
  TerrakubeHostname="localhost:$API_PORT" \
  AzBuilderExecutorUrl="http://localhost:$EXECUTOR_PORT/api/v1/terraform-rs" \
  PatSecret="$PAT_SECRET" InternalSecret="$INTERNAL_SECRET" \
  DexIssuerUri=http://localhost:5556/dex DexClientId=validation TerrakubeUiURL=http://localhost:3000 \
  spring_profiles_active=demo AgentSsrfBlockPrivateNetworks=false TerraformLoginEnabled=false \
  TerrakubeToolsRepository="$TOOLS_URL" TerrakubeToolsBranch=main \
  CustomTerraformReleasesUrl=https://releases.hashicorp.com/terraform/index.json \
  TerrakubeRedisHostname=localhost TerrakubeRedisPort="$REDIS_PORT" TerrakubeRedisSSL=false TerrakubeRedisPassword="$REDIS_PASSWORD" \
  ReconciliationHeartbeatGracePeriodSeconds=20 \
  java -Duser.home="$WORK/home" -Xmx768m -jar "$API_JAR" > "$WORK/api.log" 2>&1 &
pids+=($!)

for _ in $(seq 1 90); do
  curl -fs "http://localhost:$API_PORT/actuator/health" > /dev/null 2>&1 && break
  sleep 2
done
curl -fs "http://localhost:$API_PORT/actuator/health" > /dev/null

start_executor() {
  env \
    SERVER_PORT="$EXECUTOR_PORT" TerrakubeEnableSecurity=true InternalSecret="$INTERNAL_SECRET" \
    TerraformStateType=LocalTerraformStateImpl TerraformOutputType=LocalTerraformOutputImpl \
    AzBuilderApiUrl="http://localhost:$API_PORT" TerrakubeApiUrl="http://localhost:$API_PORT" \
    TerrakubeRegistryDomain=localhost:8075 ExecutorFlagBatch=false ExecutorFlagDisableAcknowledge=false \
    TerrakubeToolsRepository="$TOOLS_URL" TerrakubeToolsBranch=main \
    CustomTerraformReleasesUrl=https://releases.hashicorp.com/terraform/index.json \
    TerrakubeRedisHostname=localhost TerrakubeRedisPort="$REDIS_PORT" TerrakubeRedisSSL=false TerrakubeRedisPassword="$REDIS_PASSWORD" \
    ExecutorLogsArchiveIntervalSeconds=5 \
    java -Duser.home="$WORK/home" -Xmx512m -jar "$EXECUTOR_JAR" > "$WORK/executor.log" 2>&1 &
  echo $!
}
EXECUTOR_PID="$(start_executor)"
pids+=("$EXECUTOR_PID")
for _ in $(seq 1 60); do
  curl -fs "http://localhost:$EXECUTOR_PORT/actuator/health" > /dev/null 2>&1 && break
  sleep 2
done

PAT_SECRET="$PAT_SECRET" API_URL="http://localhost:$API_PORT" STORAGE_DIR="$WORK/home/.terraform-spring-boot/local/output" \
  REPO_URL="file://$WORK/repo" EXECUTOR_PID="$EXECUTOR_PID" \
  python3 "$HERE/scenario.py" "$SCENARIO"
