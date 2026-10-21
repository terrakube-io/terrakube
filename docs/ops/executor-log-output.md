# Executor log output

What the executor does with the output of a step (the lines Terraform or OpenTofu and the scripts write), and the settings that control it.

## Where the output goes

* **The saved output** of the step: every line, whole, written to storage when the step ends. This is what the Console tab and the download show once the step is over. It is not affected by the settings below.
* **The live stream** that viewers read while the step runs (Redis, or the API when `io.executor.log-via-api=true`). It is bounded.
* **The executor's own service log** (what `kubectl logs` shows for the executor). The output is also written here by default, as before. It can be switched off.

## Settings

| Environment variable | Default | Meaning |
|---|---|---|
| `ExecutorLogsEchoToServiceLog` | `true` | Also write every output line to the executor's service log (what `kubectl logs` shows). On by default, as before. Set it to `false` to stop the cluster's log pipeline ingesting the output a second time; the job's Console and the download are not affected. |
| `ExecutorLogsLiveLineMaxBytes` | `65536` | The largest line, in bytes, sent to the live stream. A longer line is cut and ends with `... [N bytes left out of the live view, the saved output has the whole line]`. The saved output keeps the whole line. `0` turns the limit off. |
| `ExecutorLogsRedisMaxLen` | `200000` | The number of entries kept in a job's live stream in Redis. With the line limit above, the stream cannot hold more than `ExecutorLogsRedisMaxLen` times `ExecutorLogsLiveLineMaxBytes` bytes of lines. |

## When the API or Redis cannot keep up

The live view is allowed to fall behind, never the run. These limits bound the memory it can use:

| Setting | Default | Meaning |
|---|---|---|
| `ExecutorLogsApiQueueMaxBytes` (executor, only with `io.executor.log-via-api=true`) | `16777216` | Bytes of lines kept while the API does not take them. Beyond it the oldest lines are left out of the live view and counted in `terrakube.executor.logs.dropped`. Before, the queue grew for as long as the API was away. The saved output is not built from this queue, so nothing is lost from it. |
| `ExecutorLogsApiBatchMaxBytes` (executor) | `1048576` | The most bytes of lines in one request to the API. A request the API refuses as too large (413) is dropped from the live view and not sent again. |
| `LogsAppendMaxBytes` (API) | `4194304` | The largest log request the API accepts, in bytes of lines. A larger one gets 413. Keep it above the executor's batch size. `0` turns it off. |
| `LogsAppendLineMaxBytes` (API) | `65536` | The largest line the API writes to the live stream when it takes logs from an executor, so an executor without the line limit cannot make the stream unbounded. |
| `ExecutorStructuredOutputQueueMaxBytes` (executor) | `67108864` | Estimated bytes of structured-output snapshots waiting to be persisted. When a new one does not fit, the oldest progress snapshots go first and a final one only after them. A single snapshot is always accepted, however large. Counted in `terrakube.executor.structured.output.dropped{reason="bytes"}`. |
| `ExecutorStructuredOutputRecoveryMaxRetainedBytes` (executor) | `67108864` | The same budget for the snapshots retained for delayed recovery. |

## Should the service log echo be on?

It is on by default so that nothing changes for anyone who reads a step's output with `kubectl logs`. For many installations it is wasted work:

* The output is already in the job's log and in the saved output, so the service log holds a second copy and the cluster's log pipeline (and its bill) ingests it twice.
* Terraform output can contain values that look like secrets, and the service log is often readable by more people than the job.

Switch it off with `ExecutorLogsEchoToServiceLog=false` when the job's log is enough.
