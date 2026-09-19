# A trigger arriving at the concurrency cap is rejected with 429, not queued

The number of Test Runs in progress is capped, because each one can use DuckDB memory (off-heap) and temporary disk that the container limit has to cover (ADR 0001). When a trigger arrives and every slot is taken, the service answers `429 Too Many Requests` with a `Retry-After` header (`shadow.retry-after`, default 30s) and creates no Test Run and no record. A `202` always means a Test Run exists; a `429` always means nothing was started, so the caller can tell which happened and retries later.

## Considered Options

- **Queue the trigger and run it when a slot frees.** Friendlier for a burst of releases, but a queue held in memory is lost when the single replica restarts (the `Recreate` strategy of ADR 0002), which would strand accepted Test Runs, and a persisted queue in MinIO would need the locking that ADR 0002 says we do not have.

## Consequences

- Callers (CI jobs) must retry on 429. The cap counts Test Runs from acceptance to the moment their result is stored, including ones that end in ERROR.
- Raising the cap means raising the container memory limit by the DuckDB `memory-limit` for each extra concurrent Test Run.
