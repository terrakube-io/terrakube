# Live output stream protocol (SSE reconnect behavior)

Covers the two Server-Sent Events streams that deliver live executor output, and exactly what a client
must do when a stream is interrupted, rotated, trimmed or closed. Tracked by #3694 (epic #3670).

| Stream | Endpoint | Source |
|---|---|---|
| Console | `GET /tfoutput/v1/organization/{organizationId}/job/{jobId}/step/{stepId}/stream` | Redis stream `<jobId>`, filtered by `stepId` |
| Structured (context) | `GET /context/v1/{jobId}/stream` | Redis stream `<jobId>-context` |

Both use `text/event-stream` and honor the standard `Last-Event-ID` request header.

## Event identifiers and resuming

- Every data event carries `id:` set to the record's own Redis Stream ID (`<ms>-<seq>`). IDs are strictly
  increasing within a stream.
- A client reconnects with `Last-Event-ID: <last id it processed>`. The server replays everything **after**
  that ID, then continues live. A client may receive a record twice around a handoff, so it MUST drop any
  event whose ID is not greater than the last one it processed (the UI does this in `useEventStream`).
- A missing, empty or unparseable `Last-Event-ID` (anything other than `<digits>` or `<digits>-<digits>`,
  for example `*`) is treated as `0-0`: a fresh viewer.
- Heartbeats are SSE comments (`: heartbeat`) every `sse-heartbeat-interval` (15s) and carry no ID.

## Ordering guarantee

For the console stream a viewer is attached to the job's shared live fan-out **before** it replays history,
and the fan-out start position is read before the viewer begins replaying. Replay then pages through Redis
(`sse-replay-page-size`, 1000) from the viewer's own cursor while live records queue for it. Records at or
below the cursor are skipped, so output is delivered in order, without gaps or duplicates, within the
retained range.

## Fresh viewers of large runs

A viewer with no cursor (`0-0`) receives only the most recent `sse-fresh-viewer-tail-records` (5000)
records, not the whole stream. If older retained records were skipped it first receives a `history-limited`
gap event (below). A viewer resuming with a real cursor always gets everything after its cursor.

## Control events

### `event: gap`: output the stream cannot provide

```
event: gap
data: {"reason":"trimmed","afterId":"<resume id>","firstAvailableId":"<id>","recovery":"step-log"}
```

Sent before any data when records between `afterId` and `firstAvailableId` are not available. The stream
continues from `firstAvailableId`; the client keeps what it has and shows that output is missing.

| `reason` | Meaning |
|---|---|
| `trimmed` | The resume ID is older than the oldest record Redis still retains. |
| `history-limited` | A fresh viewer was given only the recent tail of a long run. |

| `recovery` | Where to get the missing output |
|---|---|
| `step-log` | The archived step log (the existing step log download / `getCurrentLogs` path). Console stream. |
| `context` | `GET /context/v1/{jobId}`. Structured stream. |

### `event: end`: why the stream is closing

```
event: end
data: {"reason":"rotate"}
```

| `reason` | Cause | Client action |
|---|---|---|
| `complete` | Job reached a terminal state and all output was sent. | Stop. Do not reconnect. |
| `rotate` | Server-side limit or error: stream reached `sse-max-stream-duration` (30 min), the shared reader stopped or could not read Redis, or a viewer hit an unexpected error. | Reconnect with `Last-Event-ID`. |
| `overflow` | This viewer fell behind its byte-bounded queue (`sse-subscriber-max-queued-bytes`, 1 MiB) and was disconnected. | Reconnect with `Last-Event-ID`. |

Clients reconnect after `rotate` and `overflow` after a short randomized delay (250ms plus up to 1.75s of
jitter, since all viewers of a job rotate together) and reset their backoff. A stream that closes **without**
an `end` event is treated as complete and not retried.

## Slow-viewer isolation (console stream)

Each viewer runs on its own virtual thread with its own byte-bounded queue. The shared reader never blocks on
a viewer: when a viewer's queue would exceed the bound it is marked slow, dropped from live delivery and sent
`end: overflow` once it can read again. Other viewers of the job are unaffected. Time spent replaying history
does not count against the live queue.

## Transport errors and server limits

| Situation | Behavior |
|---|---|
| Network drop / client abort | No event. Client retries with exponential backoff (1s doubling to 30s), resuming from `Last-Event-ID`. After reaching the ceiling the UI reports the stream as failed; it recovers on the next successful event. |
| Per-pod connection cap (`sse-max-connections`, 1500) | HTTP `503` with `Retry-After: 5` before the stream opens. |
| Redis error while replaying (console) | `end: rotate`, then the client resumes from its cursor. |
| Redis error reading the fan-out start position | `end: rotate` for all viewers of the job. |
| Unexpected error on the structured stream | The emitter completes with an error and **no** `end` event; the client retries with normal backoff. |
| Concurrent replay reads | Pod-wide limit of `sse-max-concurrent-replay-reads` (16); extra replays wait. Only the Redis read holds a permit, not the send. |

## Configuration

All under `io.terrakube.logs` (`LogsProperties`):

| Property | Default |
|---|---|
| `sse-max-connections` | 1500 |
| `sse-max-stream-duration` | 30m |
| `sse-heartbeat-interval` | 15s |
| `sse-job-idle-timeout` | 2s |
| `sse-subscriber-max-queued-bytes` | 1048576 |
| `sse-fresh-viewer-tail-records` | 5000 |
| `sse-replay-page-size` | 1000 |
| `sse-max-concurrent-replay-reads` | 16 |

Overall byte budgets and incremental archival are out of scope here (#3695).
