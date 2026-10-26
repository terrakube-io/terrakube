# Output delivery validation

Checks the guarantees in [docs/ops/output-delivery-contract.md](../../../docs/ops/output-delivery-contract.md) against a running API and executor, which the unit tests cannot do because they never run the two together.

`run.sh` starts the API and the executor as plain jars (H2, local storage, a Redis you provide), runs one scenario from `scenario.py` and stops them. There are no images, no ingress and no identity provider: the scenario signs its own token with the throwaway secret that `run.sh` gives the API. It takes about 1.5 minutes after the jars are built.

| Scenario | What it checks |
|---|---|
| `kill-mid-step` | A step prints a line a second. The executor is killed with SIGKILL once a part of the output is in storage. The reconciliation sweep fails the job, and the Console still serves what was printed, marked `X-Terrakube-Output-Incomplete: true` and not cacheable. |

## Run it

```bash
mvn -B -pl api,executor -am package -DskipTests -Dspring-boot.build-image.skip=true
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --requirepass password123456
API_JAR='api/target/api-server-*.jar' EXECUTOR_JAR='executor/target/executor-*.jar' \
  scripts/validation/output-delivery/run.sh kill-mid-step
```

Set `WORK_DIR` to keep the logs, `REDIS_PORT` and `REDIS_PASSWORD` if your Redis differs. Needs Java 25, python3, git and curl. The step runs a BASH script. The workspace and the extensions repository are local git repositories created by `run.sh`, so the scenario does not depend on any outside service.

## In CI

`.github/workflows/output-delivery-validation.yml` runs it once a week, on demand, and on pull requests and pushes to `main` that touch the output delivery code (path filter). It is a single job with a 20 minute limit and keeps the API and executor logs for 7 days when it fails. It is not a required check: a required check that a path filter skips would block unrelated pull requests, and the weekly run catches anything the filter misses.

## Adding a scenario

Add a function to `scenario.py`, register it in `SCENARIOS`, and add a row above. Keep each scenario short: every extra one costs a full API and executor start.
