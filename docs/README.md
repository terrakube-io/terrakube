# Terrakube repository docs

The full user and administrator documentation is at [docs.terrakube.io](https://docs.terrakube.io/). The pages in this folder go with the code in this repository: operational runbooks and reference for behaviour that is easier to explain next to the source.

## Operations

| Page | Read it when |
| --- | --- |
| [Executor log output](ops/executor-log-output.md) | You want to know where step output goes and which settings control it. |
| [Live output stream protocol](ops/live-output-stream-protocol.md) | You are writing a client for the live output streams, or debugging a stream that reconnects or closes. |
| [Output delivery contract](ops/output-delivery-contract.md) | You want to know what output survives an executor dying, and what can still be lost. |
| [Structured output spool recovery](ops/structured-output-spool-recovery.md) | You are upgrading the API and executors, or a step's structured result is missing after an outage. |
| [Inactive runs and approval timeouts](ops/inactive-jobs-and-approval-timeouts.md) | Runs are being closed automatically, or a run is stuck waiting for approval. |
| [Zero-pending job reconciliation](ops/zero-pending-job-reconciliation.md) | A run is stuck with nothing left to execute and the executor queue is blocked. |
| [Per-job Terraform variables](ops/job-variables.md) | You want to pass `TF_VAR_*` values to a single run. |

## Templates

| Page | Read it when |
| --- | --- |
| [Plan artifacts](templates/plan-artifacts.md) | The apply step cannot find a file that the plan step built. |
| [Require a saved plan](templates/require-saved-plan.md) | You want an apply step to run only from the plan that was saved for the job. |

## Contributing to these pages

* Use sentence case for titles and headings, and give every code block a language (`text` if there is none).
* Link to other pages with relative paths so the links work in a clone.
* Put images in `docs/images/` and give each one alt text.
