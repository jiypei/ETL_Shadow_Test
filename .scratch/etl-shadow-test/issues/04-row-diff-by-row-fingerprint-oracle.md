# 04 — Row Diff by Row Fingerprint (Oracle)

**What to build:** When the Aggregate Check disagrees for an Oracle Target, the service runs a Row Diff. Oracle computes each row's key and Row Fingerprint (over the non-ignored columns, normalized by the comparison rules) in both Environments; an embedded DuckDB diffs the two sets and finds keys present on only one side and keys whose fingerprints differ. For a bounded sample of those keys, full rows from both Environments are fetched and written as a Parquet file of sampled Mismatches in MinIO, and the Test Run record points to it. When the Aggregate Check agrees, no Row Diff runs and the record says so. See ADR 0001.

**Blocked by:** 03 — Full Aggregate Check for Oracle Targets

**Status:** ready-for-agent

- [ ] A Target with differing rows gives FAIL, and the sampled Mismatches include the key and the values from both Environments
- [ ] Keys that exist only in Staging, and keys that exist only in Production, are both reported
- [ ] The number of sampled Mismatches is capped by a configurable limit; the default is chosen and documented
- [ ] A Target whose Aggregate Check agrees does not run a Row Diff, and the Test Run record states that it was skipped
- [ ] The Test Run record links to the sampled Mismatches file
- [ ] Each Test Run uses its own DuckDB working space, which is removed when the Test Run ends
- [ ] The Row Fingerprint uses the same normalization as the Aggregate Check: exact decimals, NULL equals NULL, strings untrimmed
