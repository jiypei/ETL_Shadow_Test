# Callbacks go only to allowed hosts, are retried a bounded number of times, and are also sent for abandoned Test Runs

A trigger may name a callback address. Because that address comes from the caller, the service would otherwise POST to whatever the caller names from inside the cluster. So:

- **Allowlist, default deny.** A callback host must be in `shadow.webhook.allowed-hosts`. With none configured, a request that names a callback is rejected (400). Redirects are never followed, and URLs with embedded credentials are refused.
- **Bounded retries.** Three attempts in total, pausing 1s then 5s (`initial-backoff`, `backoff-multiplier`, `max-attempts`). The outcome (`DELIVERED` or `FAILED`, attempts, last error) is written to `run.json`. A failed callback never changes the Verdict and is never retried later; callers can always poll.
- **Abandoned Test Runs are swept.** A dead instance cannot send its own callback, so the running instance periodically (`shadow.reaper-interval`) finds records that still say RUNNING with a stale heartbeat, stores them as ABANDONED with Verdict ERROR, and sends their callback once. Polling and history report the same state either way.

## Considered Options

- **Allow any http(s) address.** Simplest, but lets a token holder make the service call internal endpoints (SSRF).
- **Retry indefinitely or from a durable queue.** Better delivery, but needs state and locking that ADR 0002 avoids, and callers can poll.

## Consequences

- The sweep scans every Pipeline's run records for RUNNING ones, so its cost grows with history; move old records out of the results bucket, or add an index, if that becomes slow.
- The callback address is stored in `run.json`, so it must not carry secrets.
