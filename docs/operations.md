# Operating the ETL Shadow Test service

Vocabulary follows `CONTEXT.md`; the reasons behind the rules are in `docs/adr/`.

## API

All `/api/**` calls need `Authorization: Bearer <token>`. A token authorizes one Pipeline and its list of Targets. `/actuator/health` needs none.

| Call | Meaning |
| --- | --- |
| `POST /api/v1/test-runs` | Trigger. Returns `202` with the Test Run record (status `RUNNING`) at once. `400` for an invalid request (nothing is created), `401`/`403` for token problems, `429` with `Retry-After` at the concurrency cap (ADR 0004). |
| `GET /api/v1/pipelines/{pipeline}/test-runs/{id}` | The Test Run record: status (`RUNNING`, `COMPLETED`, `ABANDONED`), Verdicts, evidence, the Comparison Config used. |
| `GET /api/v1/pipelines/{pipeline}/test-runs?from=&to=&limit=` | History, most recent first. `from` inclusive and `to` exclusive (ISO instants), `limit` 1 to 500 (default 50). |

Request body: `pipeline`, `config.targets[]`, optional `scope` (`from` inclusive, `to` exclusive) and optional `callbackUrl`. Unknown fields are rejected, so connection details and credentials can never be supplied.

Each Target: `name`, `type` (`ORACLE` or `PARQUET`), `staging` and `production` locations (`schema` and `table` for Oracle, `path` inside the Environment's bucket for Parquet), and exactly one of `keyColumns` or `keyless: true`, and exactly one of `scopeColumn` or `fullRefresh: true` (a `newTarget: true` Target needs neither). Optional: `ignoredColumns`, `tolerances` (absolute, per numeric column).

## Rules worth knowing

- Comparison is exact: Oracle `NUMBER` as decimal, NULL equals NULL, strings untrimmed, dates and timestamps as timestamps. Types that cannot be compared (LOBs, RAW, booleans, intervals) must be listed in `ignoredColumns`.
- Floating-point columns (Oracle `BINARY_FLOAT`/`BINARY_DOUBLE`, Parquet `FLOAT`/`DOUBLE`): their sum depends on summation order, so it is reported as `informational` (`decisive: false`) and cannot fail a Target by itself; the exact per-value checksum, null counts and Row Diff decide. A column with a tolerance is still checked by sum, min and max within the tolerance. Decimal sums stay exact and decisive.
- A column present on one side only, or with a different type, is a schema difference and a FAIL.
- A Target found in neither Environment is a rejected typo; found in one only it is an ERROR (ADR 0005). A Pipeline in which every Target is SKIPPED has Verdict SKIPPED.
- Sampled Mismatches: at most `mismatch-sample-size` (default 100) per Mismatch type and Target, written to `results/{pipeline}/{id}/mismatches/{target}.parquet`.
- Callbacks: see ADR 0006.

## Configuration (`shadow.*`)

| Property | Default | Purpose |
| --- | --- | --- |
| `staging.oracle`, `production.oracle` | required | `url`, `username`, `password`, `max-connections` (4), `max-parallel-queries` (2, never above `max-connections`), `query-timeout` (30m), `connection-timeout` (10s). Production only: `replica-url` sends every query to the read replica instead of `url`. Use a read-only account for Production. |
| `staging.minio`, `production.minio`, `results.minio` | required | `endpoint`, `access-key`, `secret-key`, `bucket`. Parquet Targets live in the Environment's bucket; results in the results bucket. |
| `tokens[]` | none (all calls rejected) | `token`, `pipeline`, `targets[]`. Provision from Kubernetes secrets. |
| `max-concurrent-runs` / `retry-after` | 2 / 30s | Concurrency cap and the hint sent with `429`. |
| `heartbeat-interval` / `heartbeat-stale-after` | 5s / 30s | A running Test Run rewrites its heartbeat this often; a record silent for longer is ABANDONED. |
| `reaper-interval` | 30s | How often abandoned Test Runs are found, stored and called back. |
| `duckdb.memory-limit` / `threads` / `temp-directory` / `extension-directory` | 1GB / 2 / system temp / DuckDB default | Per Test Run. DuckDB memory is off-heap. |
| `mismatch-sample-size` | 100 | See above. |
| `webhook.allowed-hosts[]` | none (callbacks disabled) | Also `max-attempts` (3), `initial-backoff` (1s), `backoff-multiplier` (5), `timeout` (10s). |

## Deployment

- One replica, `Recreate` strategy (ADR 0002). Container memory limit = JVM heap + `max-concurrent-runs` x `duckdb.memory-limit` + 768MB for the three small fixed DuckDB instances (256MB each, for history and for reading Parquet schemas in Staging and Production) + headroom, and give `duckdb.temp-directory` enough disk for a Row Diff on your largest Target.
- DuckDB installs its `httpfs` extension on first use; on a cluster without internet access, pre-install it and set `duckdb.extension-directory`.
- Parquet in the Staging, Production and results buckets is reached through one DuckDB secret per bucket, so the three buckets must have different names.
