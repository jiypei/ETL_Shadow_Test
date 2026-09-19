# 07 — Parquet Targets

**What to build:** A Target of type Parquet, given as a MinIO location in each Environment, is compared with the same rules and tiers as an Oracle Target. DuckDB reads the Parquet data straight from MinIO for the Aggregate Check and for key plus Row Fingerprint, and the Row Diff proceeds as in ticket 04. A Parquet Target is always compared with a Parquet Target.

**Blocked by:** 04 — Row Diff by Row Fingerprint (Oracle)

**Status:** ready-for-agent

- [ ] Identical Parquet data in both Environments gives PASS
- [ ] Differing Parquet data gives FAIL, with sampled Mismatches showing values from both Environments
- [ ] Comparison Scope, ignored columns, keyless Targets and duplicate keys work for Parquet Targets
- [ ] Parquet data is read directly from MinIO, without copying whole datasets into the service first
- [ ] A Parquet Target whose location is missing in one Environment is not silently treated as empty
