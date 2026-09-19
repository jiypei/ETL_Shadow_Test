# Test Run results are stored in MinIO, not a database

Each Test Run writes `results/{pipeline}/{testRunId}/run.json` (status, heartbeat, Verdicts, the Comparison Config used) and `mismatches/{target}.parquet` (sampled Mismatches) to MinIO. We chose this over an Oracle schema because DuckDB already queries JSON and Parquet on MinIO, so history queries need no extra database and no schema migrations.

## Consequences

- MinIO gives no transactions or locks. Status changes overwrite the single `run.json`, which is atomic per object, and polling reads only that object.
- A Test Run whose pod dies would stay RUNNING forever, so the running pod updates a heartbeat in `run.json` and a stale heartbeat is reported as ERROR (abandoned).
- Concurrency limits cannot be coordinated across pods through MinIO, so the first version runs a single replica with the `Recreate` deployment strategy.
