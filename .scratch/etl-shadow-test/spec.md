# ETL Shadow Test service

Status: ready-for-agent

Vocabulary follows `CONTEXT.md`. Decisions follow `docs/adr/0001` to `0003`.

## Problem Statement

Before a changed ETL Pipeline is released, the team has no systematic way to know whether its new code produces the same output as the code running in Production. Pipelines mostly consolidate Oracle tables into another Oracle database or into Parquet files on MinIO. Checking by hand is slow and misses subtle differences (rounding, NULL handling, duplicated keys, dropped rows), so regressions reach Production. The largest Targets are around 20 million rows and 100GB, so a naive full comparison is too expensive to run routinely, and Production must not be put at risk while comparing.

## Solution

A Kotlin/Spring Boot service on Kubernetes. After a Pipeline finishes in Staging, the caller triggers a Shadow Test. The service returns a Test Run ID immediately, compares each Target that the new version wrote in Staging with the same Target that the released (old) version wrote in Production, and produces a Verdict per Target and for the whole Pipeline. An Oracle Target is always compared with an Oracle Target, and a Parquet Target with a Parquet Target. The caller polls for the Verdict (optionally also receiving a webhook), and the outcome, its evidence and the exact Comparison Config used are kept for later review.

Comparison is tiered: a cheap Aggregate Check first, and a Row Diff (by Row Fingerprint) only when the Aggregate Check disagrees. Only data in the Comparison Scope, meaning settled data, is compared. Production is only ever read, using a read-only account.

## User Stories

1. As a Pipeline developer, I want to trigger a Shadow Test when my Staging run finishes, so that I learn whether my change matches Production before release.
2. As a Pipeline developer, I want the trigger to return a Test Run ID immediately, so that my deployment job is not blocked by a long comparison.
3. As a Pipeline developer, I want to poll a Test Run's status with its ID, so that a CI job can wait for the Verdict.
4. As a Pipeline developer, I want an optional webhook callback when a Test Run completes, so that I do not have to poll.
5. As a Pipeline developer, I want a Verdict per Target, so that I can see exactly which Target failed.
6. As a Pipeline developer, I want a Pipeline-level Verdict that is FAIL if any Target fails, so that one bad Target blocks release.
7. As a Pipeline developer, I want a comparison that could not complete to be reported as ERROR and never as PASS, so that a broken check cannot look like a green one.
8. As a Pipeline developer, I want a Pipeline-level ERROR when no Target failed but some could not be compared, so that I know the result is inconclusive.
9. As a Pipeline developer, I want to keep the Comparison Config in my Pipeline's repository and send it with each trigger, so that it is reviewed together with my code.
10. As a Pipeline developer, I want to name a Comparison Scope range per Test Run, so that only settled data is compared.
11. As a Pipeline developer, I want to declare a scope column per Target in the Comparison Config, so that the range applies to the right column.
12. As a Pipeline developer, I want to declare a Target as a Full-Refresh Target, so that it is compared whole.
13. As a Pipeline developer, I want Mismatches on a Full-Refresh Target flagged as possibly caused by source drift, so that I do not chase a false alarm.
14. As a Pipeline developer, I want to declare key columns per Target, so that Staging and Production rows can be matched.
15. As a Pipeline developer, I want duplicate keys on either side to be reported as a FAIL, so that data corruption is not hidden.
16. As a Pipeline developer, I want to declare a Target keyless, so that tables with no unique key can still be checked.
17. As a Pipeline developer, I want a Keyless Target's report to show only count differences of identical rows, so that I understand the limits of what was checked.
18. As a Pipeline developer, I want to declare ignored columns, so that non-deterministic columns such as sequence numbers or load timestamps do not cause false Mismatches.
19. As a Pipeline developer, I want to declare a numeric tolerance for specific columns, so that harmless floating-point differences are accepted.
20. As a Pipeline developer, I want the report to say how tolerance columns were checked, so that I do not believe they were fully row-compared.
21. As a Pipeline developer, I want any difference not permitted by the Comparison Config to be a Mismatch, so that nothing is accepted implicitly.
22. As a Pipeline developer, I want Oracle numbers compared as exact decimals, so that precision loss is never silently accepted.
23. As a Pipeline developer, I want NULL to equal NULL, so that rows with missing values are not flagged spuriously.
24. As a Pipeline developer, I want strings compared without trimming, so that padding differences are caught.
25. As a Pipeline developer, I want dates and timestamps compared as timestamps, so that time components are not lost.
26. As a Pipeline developer, I want to mark a Target as a New Target, so that a Pipeline's first release, with no Production counterpart, is skipped and clearly labelled instead of failing.
27. As a Pipeline developer, I want a Target missing in Production, without the New Target flag, to be an ERROR, so that a typo or missing deployment is not treated as success.
28. As a Pipeline developer, I want a sample of differing rows in the result, with the values from both Environments, so that I can diagnose Mismatches without rerunning.
29. As a Pipeline developer, I want the number of sampled Mismatches to be bounded, so that results stay small and readable.
30. As a Pipeline developer, I want the result to include the Comparison Config that was actually used, so that I can tell later what was checked.
31. As a Pipeline developer, I want Aggregate Check results (counts, sums, null rates, checksums) reported per Target, so that I see the size of a difference at a glance.
32. As a Pipeline developer, I want an unchanged Target to finish after the Aggregate Check alone, so that routine runs are cheap.
33. As a Pipeline developer, I want both Oracle and Parquet Targets supported, so that all my Pipelines can be validated with one tool.
34. As a Pipeline developer, I want a request rejected when it names a table or column that does not exist, so that typos are found immediately.
35. As a Pipeline developer, I want a request rejected when it contains anything other than declared names and values (for example a SQL fragment), so that the service cannot be misused.
36. As a release manager, I want to query the history of Verdicts for a Pipeline, so that I can judge whether it is ready to release.
37. As a release manager, I want the history to show for each Test Run its scope, Verdicts and time, so that I can see trends and gaps.
38. As a release manager, I want Cutover Readiness left as my own decision based on history, so that the tool does not impose a policy that does not fit our process.
39. As a platform engineer, I want each caller to authenticate with a token limited to one Pipeline and its Targets, so that a leaked token cannot read other data.
40. As a platform engineer, I want the service to use only a read-only account for Production, so that it can never modify Production.
41. As a platform engineer, I want limits on connections, parallelism and query time against Production, so that comparisons cannot degrade it.
42. As a platform engineer, I want Production comparison queries directed to a read replica when one is configured, so that the primary is spared.
43. As a platform engineer, I want a cap on concurrent Test Runs, so that DuckDB memory and temporary disk stay within the container limits.
44. As a platform engineer, I want DuckDB memory and temporary directory configurable, so that I can size the pod.
45. As a platform engineer, I want a Test Run whose service instance died to be reported as ERROR (abandoned) after its heartbeat goes stale, so that callers do not wait forever.
46. As a platform engineer, I want to trigger a fresh Test Run after an abandoned one, so that the caller can recover without manual cleanup.
47. As a platform engineer, I want Environment connections and credentials kept in service configuration and never accepted in requests, so that callers cannot point the service at arbitrary systems.
48. As a platform engineer, I want a single replica with recreate-style deployment in the first version, so that concurrency limits hold without distributed coordination.
49. As an auditor, I want every Test Run to keep its Verdicts, evidence and Comparison Config, so that a past release decision can be reconstructed.
50. As a Pipeline developer, I want concurrent Test Runs for different Pipelines not to interfere, so that teams can release independently.

## Implementation Decisions

**Modules**
- **Public API**: three capabilities. Trigger a Test Run (returns its ID at once), get a Test Run's status and Verdicts, and list a Pipeline's Test Run history.
- **Authentication and authorization**: bearer token per Pipeline, provisioned as a Kubernetes secret; each token authorizes one Pipeline and its list of Targets only.
- **Comparison Config validator**: checks the request against the real Oracle metadata or Parquet schema before any SQL is built. Names are quoted as identifiers. No raw SQL fragments are accepted anywhere in the request.
- **Test Run orchestrator**: runs each Test Run asynchronously, enforces the concurrency cap, updates a heartbeat, and computes the Pipeline Verdict from the Target Verdicts.
- **Oracle Target adapter**: runs the Aggregate Check as SQL in Oracle, computes key plus Row Fingerprint in Oracle, and fetches full rows for the sampled differing keys. For Production, it uses the read-only account, a read replica when configured, and limits on connections, parallelism and query time.
- **Parquet Target adapter**: DuckDB reads MinIO directly for the Aggregate Check and for key plus Row Fingerprint.
- **Row Diff engine**: an embedded DuckDB diffs the key plus Row Fingerprint sets from both Environments (ADR 0001) and yields differing keys, duplicate keys, and for Keyless Targets the fingerprint count differences.
- **Results store**: writes each Test Run's record and sampled Mismatches to MinIO (ADR 0002) and answers history queries through DuckDB.
- **Webhook notifier**: optional callback on completion.

**Test Run lifecycle and Verdicts**
- Triggering returns a Test Run ID immediately. The Test Run then runs to completion, ERROR or abandoned.
- Verdict values are PASS, FAIL, ERROR and SKIPPED. ERROR (comparison could not complete, for example a connection failure, a Target missing on either side, or a timeout) is never PASS.
- A New Target is SKIPPED and does not count toward the Pipeline Verdict; the report clearly lists it as unverified.
- The Pipeline Verdict is FAIL if any Target is FAIL, otherwise ERROR if any is ERROR, otherwise PASS.
- The service records a heartbeat in the Test Run record while it runs. A stale heartbeat is reported as ERROR with reason abandoned. Triggering again creates a new Test Run.

**Comparison Config (part of the request)**
- Per Target: its type, declared once and applying to both Environments (an Oracle table or a MinIO Parquet dataset); its location in Staging and its location in Production; key columns; ignored columns; numeric tolerances per column; the scope column, or a declaration that it is a Full-Refresh Target; whether it is keyless; whether it is a New Target.
- Environment connection details and credentials are service configuration, never request content.
- The Comparison Config used is stored with the Test Run.

**Comparison rules (ADR 0001, plus decisions taken in the design session)**
- Comparison covers the Comparison Scope only, using the scope column and the range given at trigger time. A Full-Refresh Target is compared whole and its Mismatches are flagged as possibly caused by source drift (ADR 0003).
- Aggregate Check per Target: row counts, sums, null rates and checksums. If it agrees, the Row Diff is skipped. If it disagrees, the Row Diff runs.
- Row Fingerprint covers non-ignored, non-tolerance columns after normalization. Numbers are exact decimals, NULL equals NULL, strings are untrimmed, dates and timestamps compare as timestamps. There are no implicit conversions.
- Columns with a tolerance are excluded from the Row Fingerprint and are checked separately, at least by an Aggregate Check that honours the tolerance. The report states how each tolerance column was checked.
- Staging and Production sides of a Target are always the same type (Oracle or Parquet), so the fingerprint function is the same on both.
- Duplicate keys on either side are a FAIL. A Keyless Target is compared by counts of identical Row Fingerprints and reports only count differences.
- Sampled Mismatches are capped at a configurable number and include the values from both Environments.

**Results and storage (ADR 0002)**
- Per Test Run in MinIO: a run record holding status, heartbeat, Verdicts, Aggregate Check results and the Comparison Config used, plus a Parquet file of sampled Mismatches per Target.
- Status changes overwrite the run record; polling reads only that record. History queries scan the run records for a Pipeline with DuckDB.

**Operational constraints**
- Production is read-only, by account permission and by design; the service never needs write access to it.
- DuckDB is embedded, with configurable memory limit and temporary directory. Its memory is outside the JVM heap, so the container memory limit must include it. The number of concurrent Test Runs is capped.
- First version runs one replica with recreate-style deployment.

## Testing Decisions

- **One seam: the public API.** Tests trigger Test Runs and poll them exactly as a caller would, and assert on Verdicts, the reported evidence and the stored results. Internal modules are not tested directly. Good tests here check external behaviour only, never implementation details such as how the SQL was built.
- **Real external systems via Testcontainers.** Two Oracle instances stand in for Staging and Production, and one MinIO provides both Parquet Targets and the results store. The service runs as it does in deployment. Oracle is real because the main risks are Oracle-specific: number precision, date handling, padded characters, empty string equals NULL, and hashing.
- **Scenarios to cover:**
  - Identical data gives PASS; differing data gives FAIL with sampled Mismatches.
  - Ignored columns, numeric tolerances, duplicate keys, keyless Targets, New Targets and Full-Refresh Targets.
  - Comparison Scope: rows outside the range are ignored.
  - A Target missing on either side, or a connection failure, gives ERROR, never PASS.
  - Pipeline Verdict combination: FAIL over ERROR over PASS, with SKIPPED not counting.
  - Config validation: unknown tables or columns and injection attempts are rejected.
  - A token can act only for its own Pipeline.
  - A stale heartbeat, written directly into MinIO by the test, gives ERROR (abandoned) when polled.
  - Production is accessed only with the read-only account, so a write attempt against it must fail.
  - Both Oracle and Parquet Targets.
  - History query returns past Verdicts for a Pipeline.
- Prior art: none, since the repository has no code yet.
- Not tested here: behaviour and performance at 100GB scale, and environment facts such as a read replica or an off-peak window. Those are checked separately.

## Out of Scope

- The policy for Cutover Readiness. Only Verdict history is provided.
- Running more than one replica, and distributed coordination of concurrency limits.
- Controlling or freezing input parity between Staging and Production (ADR 0003).
- Comparing an Oracle Target against a Parquet Target.
- Any write access to Production, and automatic fixing of Mismatches.
- A user interface. The service is API-only.
- Performance and load testing at the full data scale.
- Sources other than Oracle tables and Parquet on MinIO.

## Further Notes

- **Open facts** that affect tuning, not the design: whether the largest Target has LOB columns (cost of fingerprinting them in Oracle is unverified), and whether a Production read replica or an off-peak window exists.
- **Known gap by design:** because tolerance columns are excluded from the Row Fingerprint (ADR 0001), a large difference confined to such a column is caught only by the Aggregate Check.
- **Assumptions not discussed in the design session**, to confirm during implementation: what happens to a trigger that arrives while the concurrent-run cap is reached (queue or reject); the default sample size for Mismatches; webhook retry behaviour; and how a Target's location in each Environment is written in the Comparison Config.
