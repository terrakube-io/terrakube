# Output delivery contract

Where the output of a run becomes recoverable, what can still be lost, and when saved output expires. This is the short version. The details and the settings are in [Executor log output](executor-log-output.md) (the Console text) and [Structured output spool recovery](structured-output-spool-recovery.md) (the structured result). Tracked by #3696 (epic #3670).

Recovery never runs Terraform or OpenTofu again. It only reads what the executor already wrote to storage.

## Console output

| Moment | What is kept | What can be lost |
|---|---|---|
| While the step runs | The live stream (Redis, or the API when `io.executor.log-via-api=true`), kept for a limited time and bounded in size. Parts in object storage once enough output has built up or the archive interval has passed. | Nothing is lost yet, but the live stream is not a record: it expires and is trimmed. |
| The executor dies before the step ends | The parts that were uploaded. The API serves them joined, in order, with the header `X-Terrakube-Output-Incomplete: true`, and the Console shows a notice. | The output printed since the last uploaded part. A short step that never reached a part keeps nothing in storage. |
| The step ends | The saved output, written whole. The parts are then deleted. | Nothing, once the upload has succeeded. If the upload fails, the parts are not deleted. |

## Structured result

The final structured result of a step has a `deliveryState` (the full list is in the spool recovery page):

| State | Recoverable | Meaning |
|---|---|---|
| `ACKED` | Yes | The API confirmed the result. |
| `SPOOLED` | Yes | The API was down, so the executor wrote the result to object storage. The API applies it when it can, and the state becomes `RECOVERED`. |
| `NOT_DURABLE` | No | The default for a running step. If the executor dies before the result reached the API or the spool, that final snapshot is gone. What the API already had stays. |
| `EXPIRED` | No | The spool was older than the retention window or was rejected as invalid. |

## Expiry and retention

* A spooled result is kept for 24 hours by default and is then marked `EXPIRED`.
* Parts are removed when the step's saved output is written. Parts left by a crash are removed with the job or workspace.
* The live stream is not kept after its Redis retention.

## Executors and storage

The mechanisms run in the executor and the API, so they are the same for permanent and ephemeral executors. Both need a storage backend the API can also read (S3, Azure Blob, GCS, or a local directory shared with the API). With a local directory that only the executor can see, nothing is recoverable after the executor is gone, and steps stay `NOT_DURABLE`.
