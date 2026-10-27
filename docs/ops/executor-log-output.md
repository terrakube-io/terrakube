# Executor log output

What the executor does with the output of a step (the lines Terraform or OpenTofu and the scripts write), and the settings that control it.

## Where the output goes

* **The saved output** of the step: every line, whole, written to storage when the step ends. This is what the Console tab and the download show once the step is over. It is not affected by the settings below.
* **The live stream** that viewers read while the step runs (Redis, or the API when `io.executor.log-via-api=true`). It is bounded.
* **The executor's own service log** (what `kubectl logs` shows for the executor). The output is also written here by default, as before. It can be switched off.

## Output kept while the step runs

The loss boundary for all output, and its expiry, is in the [output delivery contract](output-delivery-contract.md).

The saved output is written once, when the step ends. If the executor dies before that (the pod is killed, the node goes away), there is nothing to read once the live stream has expired. So the executor also copies the output to storage in parts while the step runs:

```text
tfoutput/<organization>/<job>/<step>.parts/000001.part
tfoutput/<organization>/<job>/<step>.parts/000002.part
tfoutput/<organization>/<job>/<step>.tfoutput        <- written when the step ends, as before
```

(Azure and the local backend use the same names without the `tfoutput/` folder in front, inside their own output container or directory.)

* The parts are numbered in order and never rewritten. A part is complete when its upload succeeded.
* When the step ends, the executor saves the saved output exactly as before, then deletes the parts. A run that finishes normally leaves no parts behind, and the saved output is the only thing the API reads.
* If a step ends with no saved output and a failed job (the executor is gone, so the reconciliation sweep fails the job and its steps), the API serves the parts joined, in order, up to the first number that is missing. The response has the header `X-Terrakube-Output-Incomplete: true` and is not cached, and the Console shows a notice that the last lines may be missing. Range requests work on the joined parts as they do on the saved output.
* The UI is served from another origin than the API, so a browser only sees `X-Terrakube-Output-Incomplete` if it is listed in `Access-Control-Expose-Headers`. The API sets it on that response. A reverse proxy or ingress that rewrites CORS headers itself (for example a Traefik `headers.accessControlExposeHeaders` list, or an nginx `cors-expose-headers` annotation) replaces it, and the Console notice does not show until `X-Terrakube-Output-Incomplete` is added to that list. The compose files in this repository already do. The Terrakube Helm chart sets no CORS headers by default, so it needs no change unless you added your own.
* A part is written when the output that has not been copied yet reaches `ExecutorLogsArchivePartBytes`, or when `ExecutorLogsArchiveIntervalSeconds` have passed and there is something new. A short step writes no part. The size of a part doubles for each further eighth of `ExecutorLogsArchiveMaxParts`, up to 64 MiB, so a very long step stays within the limit; past the limit the executor stops copying until the step ends.
* A part that cannot be uploaded is tried again, with a growing pause. It never fails the step: the saved output at the end is still the source of truth. Parts written and failed are counted in `terrakube.executor.output.parts{result="written|failed"}`.
* If the saved output cannot be uploaded when the step ends and parts exist, the executor leaves the parts in place, completes the step and the job as failed, and saves no output. The API then serves the parts with the incomplete notice, as for an executor that died. A step that wrote no part has nothing else in storage, so there the failure is reported as before.
* The parts are read from the executor's buffer (memory, then its temporary file), from where the last part ended. They add no copy of the output in memory.
* Deleting a workspace or job removes the parts together with the rest of the job's output, so a part left behind by a crash does not outlive the job.
* Versions can be mixed during an upgrade. An older API never lists the parts and still reads the saved output. An older executor writes no parts and the API behaves as before.

## Settings

| Environment variable | Default | Meaning |
|---|---|---|
| `ExecutorLogsEchoToServiceLog` | `false` | Also write every output line to the executor's service log (what `kubectl logs` shows). Off by default: the output is already in the job's log and in the saved output, so a copy here is ingested a second time by the cluster's log pipeline. Set it to `true` to read a step's output with `kubectl logs`; the job's Console and the download are not affected either way. |
| `ExecutorLogsLiveLineMaxBytes` | `65536` | The largest line, in bytes, sent to the live stream. A longer line is cut and ends with `... [N bytes left out of the live view, the saved output has the whole line]`. The saved output keeps the whole line. `0` turns the limit off. |
| `ExecutorLogsStepOutputMemoryBytes` | `4194304` | How much of a plan, apply or destroy step's output the executor keeps in memory for the saved output. Past it the output moves to a temporary file next to the step's working directory (the executor's scratch disk, not `/tmp`), and the upload to storage reads from that file, so a step that prints hundreds of megabytes does not need that much heap. The file is deleted when the output has been saved. The saved output is the same object as before and still has every line. `0` keeps everything in memory. |
| `ExecutorLogsRenderedPlanContextMaxBytes` | `49152` | How much of a plan's rendered text (the classic `terraform show` diff) is published to the job context, which is what the pull request comment shows. A longer plan is cut there with a closing note that says how many lines and KiB were left out. The step's output always has the whole diff, and the executor no longer collects the rendered plan in one string: it streams it to the step output line by line. `0` publishes all of it. The API refuses a value above 1 MiB. |
| `ExecutorLogsArchiveEnabled` | `true` | Copy the output of a running step to storage in parts (see above). `false` writes the output once, when the step ends, as before. |
| `ExecutorLogsArchivePartBytes` | `1048576` | Write a part when this many bytes of output have not been copied yet. |
| `ExecutorLogsArchiveIntervalSeconds` | `30` | Also write a part after this long, if there is anything new, however small. |
| `ExecutorLogsArchiveMaxParts` | `1000` | The most parts written for one step. Past it the executor stops copying until the step ends. |
| `ExecutorLogsRedisMaxLen` | `200000` | The number of entries kept in a job's live stream in Redis. With the line limit above, the stream cannot hold more than `ExecutorLogsRedisMaxLen` times `ExecutorLogsLiveLineMaxBytes` bytes of lines. |

## When the API or Redis cannot keep up

The live view is allowed to fall behind, never the run. These limits bound the memory it can use:

| Setting | Default | Meaning |
|---|---|---|
| `ExecutorLogsApiQueueMaxBytes` (executor, only with `io.executor.log-via-api=true`) | `16777216` | Bytes of lines kept while the API does not take them. Beyond it the oldest lines are left out of the live view and counted in `terrakube.executor.logs.dropped`. Before, the queue grew for as long as the API was away. The saved output is not built from this queue, so nothing is lost from it. |
| `ExecutorLogsApiBatchMaxBytes` (executor) | `1048576` | The most bytes of lines in one request to the API. A request the API refuses as too large (413) is dropped from the live view and not sent again. |
| `LogsAppendMaxBytes` (API) | `4194304` | The largest log request the API accepts, in bytes of lines, where every line also counts 96 bytes for its own overhead (so a request of many empty lines is bound too). A larger one gets 413. Keep it above the executor's batch size. `0` turns it off. |
| `LogsAppendLineMaxBytes` (API) | `65536` | The largest line the API writes to the live stream when it takes logs from an executor, so an executor without the line limit cannot make the stream unbounded. |
| `ExecutorStructuredOutputQueueMaxBytes` (executor) | `67108864` | Estimated bytes of structured-output snapshots waiting to be persisted. When a new one does not fit, the oldest progress snapshots go first and a final one only after them. A single snapshot is always accepted, however large. Counted in `terrakube.executor.structured.output.dropped{reason="bytes"}`. |
| `ExecutorStructuredOutputRecoveryMaxRetainedBytes` (executor) | `67108864` | The same budget for the snapshots retained for delayed recovery. |

## Why the service log echo is off

Before, every output line was also written to the executor's own log. It is off by default now:

* The output is already in the job's log and in the saved output, so the service log held a second copy and the cluster's log pipeline (and its bill) ingested it twice.
* Terraform output can contain values that look like secrets, and the service log is often readable by more people than the job.

Switch it on with `ExecutorLogsEchoToServiceLog=true` if you read a step's output with `kubectl logs` or ship executor logs to a platform you search for it.
