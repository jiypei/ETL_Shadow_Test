# ETL Shadow Test

A service that verifies an ETL Pipeline before release. After a Pipeline finishes in Staging, the caller triggers a **Shadow Test**. The service compares the Pipeline's Staging **Targets** (Oracle tables or Parquet datasets in MinIO) with the Production Targets and returns a **Verdict**: PASS, FAIL, ERROR or SKIPPED.

The Pipelines themselves live in other repositories. This repository is only the comparison harness.

Built with Kotlin 2.2, Spring Boot 3.5 and JDK 21, built with Gradle.

- [How it works](#how-it-works)
- [Architecture](#architecture)
- [Design decisions](#design-decisions)
- [Using the service](#using-the-service)
- [Configuration](#configuration)
- [Deployment](#deployment)
- [Development](#development)
- [Further reading](#further-reading)

## How it works

```
CI (after the Staging run)                 Shadow Test service
        |                                          |
        |-- POST /api/v1/test-runs --------------->|  validate request against real metadata
        |<-- 202 + Test Run ID --------------------|  (400 typo, 401/403 token, 429 at cap)
        |                                          |
        |                                          |  per Target, in the background:
        |                                          |    1. Aggregate Check   (cheap)
        |                                          |    2. Row Diff          (only if 1 disagrees)
        |                                          |  write results to MinIO
        |-- GET .../test-runs/{id}  (poll) ------->|
        |<-- Verdicts, evidence, config used ------|
        |<-- optional webhook callback ------------|
```

1. **Trigger.** The caller sends the Pipeline name, the **Comparison Scope** (the settled range of data to compare) and the **Comparison Config** (per-Target keys, ignored columns and tolerances). The config lives in each Pipeline's repo and is sent with every request, so nothing is registered ahead of time. The service answers at once with a Test Run ID.
2. **Aggregate Check.** Row counts, sums of numeric columns, null counts per column and a checksum over every row's fingerprint are computed on both sides (tolerance columns also get min and max). For Oracle Targets this is SQL inside Oracle. For Parquet Targets it is DuckDB reading MinIO directly. If everything agrees, the Target is PASS.
3. **Row Diff.** Only if the Aggregate Check disagrees. Key plus **Row Fingerprint** are computed where the data lives and diffed in an embedded DuckDB. Full rows are then fetched only for a sample of differing keys (default 100 per Mismatch type), so the largest Targets (about 20M rows, 100GB) never move in full.
4. **Result.** A run record and the sampled Mismatches are stored in MinIO. The caller polls for the Verdict or receives a webhook.

### Verdicts

| Verdict | Meaning |
| --- | --- |
| `PASS` | The Target matches within the Comparison Config. |
| `FAIL` | A Mismatch was found: differing rows, rows on one side only, duplicate keys, or a schema difference. |
| `ERROR` | The comparison could not be completed (unreachable database, a Target found in only one Environment, an abandoned run). ERROR is never treated as PASS. |
| `SKIPPED` | A **New Target** with no Production counterpart yet. It is not verified and does not count. |

A Pipeline is FAIL if any Target is FAIL, otherwise ERROR if any is ERROR, otherwise SKIPPED if every Target was skipped, otherwise PASS.

### Comparison rules

Comparison is **exact by default**. Oracle `NUMBER` compares as decimal, NULL equals NULL, strings are not trimmed, and there are no implicit conversions. Every exception must be written in the Comparison Config: ignored columns and numeric tolerances. Types that cannot be compared (LOBs, RAW, booleans, intervals) must be listed in `ignoredColumns`.

More rules are in [`docs/operations.md`](docs/operations.md), including how floating-point sums, schema differences and tolerance columns are handled.

## Architecture

The system is one Spring Boot service, deployed as a single replica.

```
                       +--------------------------------------------+
   CI caller --------->|  api  ->  auth  ->  validation             |
   (bearer token)      |               |                            |
                       |               v                            |
                       |  run  (Test Run service, heartbeat, sweep) |
                       |               |                            |
                       |               v                            |
                       |  compare  (per Target: Aggregate Check,    |
                       |            Row Diff)                       |
                       |          |                   |             |
                       |   target (shared seams: SqlDialect,        |
                       |           TargetSide / TargetReader)       |
                       |      /                       \             |
                       | oracle adapter          parquet adapter    |
                       +------|-----------------------|-------------+
                              v                       v
                Staging / Production Oracle    Staging / Production MinIO
                (production is read-only)      (read through DuckDB httpfs)

        results: MinIO results bucket  <-- run.json, mismatches/*.parquet, history via DuckDB
```

| Package | Responsibility |
| --- | --- |
| `api` | REST controller, request and error types. |
| `auth` | Bearer tokens. Each token maps to one Pipeline and its list of Targets. |
| `validation` | Checks the request against the real Oracle metadata or Parquet schema before any name is used in SQL. |
| `run` | Test Run service, the run record (`run.json`), heartbeat and the sweep of abandoned runs. |
| `compare` | Per-Target comparison, Aggregate Check comparison and the Row Diff engine. |
| `target` | What is shared between databases: `AggregateQuery`, `RowFetch`, `KeyFingerprintTable`, column categories, and the `SqlDialect` and `TargetSide`/`TargetReader` seams. |
| `oracle`, `parquet` | The two adapters: a SQL dialect for each and how each runs its queries. |
| `duckdb` | The per-run DuckDB workspace, and S3 (MinIO) access. |
| `results` | MinIO run records and history queries. |
| `webhook` | Callback delivery. |

The Oracle and Parquet adapters sit behind one seam, so the comparison logic in `compare` is written once. Both sides of a comparison are always the same Target type, so the Row Fingerprint function is identical on both sides.

## Design decisions

The reasons behind rules that could look wrong without context are recorded as ADRs in [`docs/adr/`](docs/adr/). Do not change them without superseding the ADR.

| ADR | Decision |
| --- | --- |
| [0001](docs/adr/0001-row-diff-by-fingerprint-in-duckdb.md) | Row Diff compares key plus Row Fingerprint in an embedded DuckDB, not full rows, to avoid moving hundreds of GB per run. |
| [0002](docs/adr/0002-test-run-results-stored-in-minio.md) | Results live in MinIO, not a database. A stale heartbeat marks a run abandoned. A single replica with the `Recreate` strategy avoids needing locks. |
| [0003](docs/adr/0003-compare-settled-scope-after-the-fact.md) | Only settled data is compared. Source parity between Environments is not controlled, so a Mismatch is evidence, not proof. |
| [0004](docs/adr/0004-reject-triggers-at-the-concurrency-cap.md) | A trigger at the concurrency cap gets `429` with `Retry-After`, never a queue. |
| [0005](docs/adr/0005-typo-versus-missing-target.md) | A Target found in neither Environment is rejected (400). Found in only one, it is an ERROR. |
| [0006](docs/adr/0006-webhook-callbacks-are-allowlisted-bounded-and-swept.md) | Callbacks go only to allowlisted hosts, with bounded retries, and are also sent for abandoned runs. |

Constraints that span the code:

- **Production is read-only.** Use a read-only account and, ideally, a read replica (`replica-url`). Connections, parallelism and query timeouts are capped.
- **No raw SQL from callers.** Table and column names from a Comparison Config are validated against the real metadata before being quoted as identifiers. Connection details and credentials can never appear in a request: unknown fields are rejected.
- **DuckDB memory is off-heap.** `memory_limit` and `temp_directory` are set per Test Run, and concurrent runs are capped.
- **Tolerance columns are excluded from the Row Fingerprint** and checked separately by Aggregate Check. Reports state how each one was checked.

The vocabulary (Target, Test Run, Comparison Scope, Row Fingerprint and so on) is defined in [`CONTEXT.md`](CONTEXT.md).

## Using the service

### Authentication

All `/api/**` calls need `Authorization: Bearer <token>`. A token authorizes one Pipeline and its list of Targets. `/actuator/health` needs no token.

### 1. Trigger a Test Run

```bash
curl -X POST https://shadow-test.example.com/api/v1/test-runs \
  -H "Authorization: Bearer $SHADOW_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pipeline": "orders-consolidation",
    "scope": { "from": "2026-09-01", "to": "2026-09-08" },
    "callbackUrl": "https://ci.example.com/hooks/shadow-test",
    "config": {
      "targets": [
        {
          "name": "ORDERS",
          "type": "ORACLE",
          "staging":    { "schema": "DWH_STG",  "table": "ORDERS" },
          "production": { "schema": "DWH_PROD", "table": "ORDERS" },
          "keyColumns": ["ORDER_ID"],
          "scopeColumn": "ORDER_DATE",
          "ignoredColumns": ["ETL_LOADED_AT"],
          "tolerances": { "TOTAL_AMOUNT": 0.01 }
        },
        {
          "name": "customer_dim",
          "type": "PARQUET",
          "staging":    { "path": "dwh/customer_dim/" },
          "production": { "path": "dwh/customer_dim/" },
          "keyColumns": ["customer_id"],
          "fullRefresh": true
        }
      ]
    }
  }'
```

The response is `202` with the Test Run record (status `RUNNING`), including its `testRunId`.

| Status | Meaning |
| --- | --- |
| `202` | A Test Run exists and is running. |
| `400` | Invalid request. Nothing was created. |
| `401` / `403` | Token missing, invalid, or not allowed for this Pipeline or Target. |
| `429` | At the concurrency cap. Nothing was started. Retry after the `Retry-After` seconds. |

CI callers must retry on `429`.

### Request fields

| Field | Notes |
| --- | --- |
| `pipeline` | Must match the token's Pipeline. |
| `config.targets[]` | The Comparison Config, one entry per Target. |
| `scope` | Optional. `from` inclusive, `to` exclusive. Required if any Target uses a `scopeColumn`. |
| `callbackUrl` | Optional. The host must be in `shadow.webhook.allowed-hosts`, otherwise the request is rejected. The URL is stored in `run.json`, so it must not carry secrets. |

Each Target:

| Field | Notes |
| --- | --- |
| `name`, `type` | `type` is `ORACLE` or `PARQUET`. |
| `staging`, `production` | Locations. Oracle: `schema` and `table`. Parquet: `path` inside the Environment's bucket. |
| `keyColumns` or `keyless: true` | Exactly one. A Target with no unique key must be declared keyless. It is then compared only as counts of identical Row Fingerprints. Duplicate keys are a FAIL. |
| `scopeColumn` or `fullRefresh: true` | Exactly one, unless `newTarget: true`. A Full-Refresh Target is compared whole, so its Mismatches are flagged as possibly caused by source drift. |
| `ignoredColumns` | Optional. Columns excluded from comparison. |
| `tolerances` | Optional. Absolute tolerance per numeric column. |
| `newTarget: true` | No Production counterpart yet. The Target is SKIPPED, not ERROR. |

### 2. Poll for the Verdict

```bash
curl -H "Authorization: Bearer $SHADOW_TOKEN" \
  https://shadow-test.example.com/api/v1/pipelines/orders-consolidation/test-runs/$TEST_RUN_ID
```

The record's `status` is `RUNNING`, `COMPLETED` or `ABANDONED` (the running instance died, reported with Verdict `ERROR`). When completed it holds the Pipeline `verdict` and, per Target: the verdict, schema differences, the Aggregate Check results, the Row Diff counts, how tolerance columns were checked, and the Comparison Config that was used.

Sampled Mismatches are stored in MinIO at `results/{pipeline}/{testRunId}/mismatches/{target}.parquet`, at most `mismatch-sample-size` per Mismatch type and Target.

### 3. Verdict history

```bash
curl -H "Authorization: Bearer $SHADOW_TOKEN" \
  "https://shadow-test.example.com/api/v1/pipelines/orders-consolidation/test-runs?from=2026-09-01T00:00:00Z&limit=20"
```

Returns the Pipeline's Test Runs, most recent first. `from` is inclusive and `to` exclusive (ISO instants). `limit` is 1 to 500 (default 50).

### Callbacks

If `callbackUrl` was given, the service POSTs the outcome when the run ends. This includes runs that were abandoned. It makes three attempts, waiting 1s and then 5s, and never follows redirects. A failed callback never changes the Verdict, so callers can always poll. The delivery outcome is written to `run.json`.

### Reading a result

- **FAIL with a Full-Refresh Target:** check the drift note before blaming the code. Source data may have changed between the two runs.
- **FAIL on a tolerance column:** the column was checked by aggregates only (sum, min, max), not row by row. The report says so under `toleranceColumns`.
- **ERROR:** the comparison did not complete. The `reason` names the Environment and the cause. Fix and re-run. Never read it as PASS.
- **SKIPPED:** nothing was verified. Do not treat a Pipeline made only of SKIPPED Targets as passing.

## Configuration

Settings live under `shadow.*` in `application.yml` or the Spring equivalents (for example the environment variable `SHADOW_STAGING_ORACLE_URL`). Connections and credentials are service configuration and never come from a request.

```yaml
shadow:
  staging:
    oracle:
      url: jdbc:oracle:thin:@//stg-db:1521/DWH
      username: shadow_ro
      password: ${STAGING_ORACLE_PASSWORD}
    minio:
      endpoint: http://minio-stg:9000
      access-key: ${STAGING_MINIO_ACCESS_KEY}
      secret-key: ${STAGING_MINIO_SECRET_KEY}
      bucket: staging-dwh
  production:
    oracle:
      url: jdbc:oracle:thin:@//prod-db:1521/DWH
      replica-url: jdbc:oracle:thin:@//prod-replica:1521/DWH   # optional, preferred
      username: shadow_ro                                       # read-only account
      password: ${PRODUCTION_ORACLE_PASSWORD}
    minio:
      endpoint: http://minio-prod:9000
      access-key: ${PRODUCTION_MINIO_ACCESS_KEY}
      secret-key: ${PRODUCTION_MINIO_SECRET_KEY}
      bucket: production-dwh
  results:
    minio:
      endpoint: http://minio-results:9000
      access-key: ${RESULTS_MINIO_ACCESS_KEY}
      secret-key: ${RESULTS_MINIO_SECRET_KEY}
      bucket: shadow-results
  tokens:
    - token: ${ORDERS_PIPELINE_TOKEN}
      pipeline: orders-consolidation
      targets: [ORDERS, customer_dim]
  webhook:
    allowed-hosts: [ci.example.com]
```

| Property | Default | Purpose |
| --- | --- | --- |
| `staging.oracle`, `production.oracle` | required | `url`, `username`, `password`, `max-connections` (4), `max-parallel-queries` (2, never above `max-connections`), `query-timeout` (30m), `connection-timeout` (10s). Production only: `replica-url`. |
| `staging.minio`, `production.minio`, `results.minio` | required | `endpoint`, `access-key`, `secret-key`, `bucket`. The three buckets must have different names. |
| `tokens[]` | none (all calls rejected) | `token`, `pipeline`, `targets[]`. Provision from Kubernetes secrets. |
| `max-concurrent-runs`, `retry-after` | 2, 30s | Concurrency cap and the hint sent with `429`. |
| `heartbeat-interval`, `heartbeat-stale-after` | 5s, 30s | A running Test Run rewrites its heartbeat this often. A record silent for longer is ABANDONED. |
| `reaper-interval` | 30s | How often abandoned Test Runs are found, stored and called back. |
| `duckdb.memory-limit`, `threads`, `temp-directory`, `extension-directory` | 1GB, 2, system temp, DuckDB default | Per Test Run. |
| `mismatch-sample-size` | 100 | Sampled Mismatches per type and Target. |
| `webhook.allowed-hosts[]` | none (callbacks disabled) | Also `max-attempts` (3), `initial-backoff` (1s), `backoff-multiplier` (5), `timeout` (10s). |

## Deployment

- **One replica with the `Recreate` strategy.** MinIO gives no locks, so the first version cannot coordinate several pods (ADR 0002).
- **Container memory limit** = JVM heap + `max-concurrent-runs` x `duckdb.memory-limit` + 768MB for the three small fixed DuckDB instances + headroom. Give `duckdb.temp-directory` enough disk for a Row Diff on your largest Target.
- **DuckDB `httpfs` extension** is installed on first use. On a cluster without internet access, pre-install it and set `duckdb.extension-directory`.
- **Production access:** read-only account only. Prefer a read replica, otherwise run off-peak.
- **Health check:** `GET /actuator/health`.

## Development

Needs JDK 21 and a running Docker. Tests start two Oracle Free containers (`gvenzl/oracle-free:23-slim-faststart`) and one MinIO (`quay.io/minio/minio`) through Testcontainers, so pull these images first.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # this machine; adjust for yours

gradle test                                # all tests
gradle test --tests '*RowDiffTest'         # one class
gradle bootRun                             # run the service (needs the shadow.* settings above)
```

- The public HTTP API is the seam for tests. They trigger and poll Test Runs and assert on Verdicts, reports and MinIO contents, and do not test internal classes directly.
- Tests that must change a connection or limit extend `ShadowTestSupport` and register properties through `TestEnvironment.registerProperties(registry, overrides)`. Other tests extend `ShadowTestBase`.
- If MinIO returns `RequestTimeTooSkewed`, the Docker VM clock has drifted (usually after sleep). Reset it with a privileged container: `docker run --rm --privileged --entrypoint sh <local image> -c "date -u -s @$(date -u +%s)"`.

## Further reading

- [`CONTEXT.md`](CONTEXT.md): the glossary. Use its terms exactly.
- [`docs/operations.md`](docs/operations.md): API reference, comparison rules, configuration and deployment.
- [`docs/adr/`](docs/adr/): architecture decision records.
