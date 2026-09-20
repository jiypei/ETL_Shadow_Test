# ETL Shadow Test

A framework that verifies an ETL pipeline before release by comparing the output its new code produced in staging against the output production produced.

## Language

**Pipeline**:
An ETL job that consolidates source tables and writes the result to a Target.
_Avoid_: Job, workflow, DAG

**Environment**:
One of the two deployments a Pipeline runs in: Staging (running the new code) or Production (running the released code).
_Avoid_: Stage, tier

**Target**:
A dataset a Pipeline writes, either an Oracle table or a Parquet dataset in MinIO.
_Avoid_: Output, sink, destination

**Shadow Test**:
The verification of one Pipeline's Staging Targets against its Production Targets, invoked after the Staging run completes.
_Avoid_: Validation, regression test, diff job

**Test Run**:
One execution of a Shadow Test, producing a Verdict for each Target and one for the Pipeline.
_Avoid_: Run (ambiguous with a Pipeline's own execution), job

**Comparison Scope**:
The part of a Target that is compared, restricted to data that is no longer changing so that source drift cannot cause false differences. Given per Test Run as a range of the Target's scope column; a Full-Refresh Target has no scope column and is compared whole.
_Avoid_: Window, filter

**Full-Refresh Target**:
A Target that a Pipeline rewrites completely on every execution, so it has no scope column. Its Mismatches may stem from source drift and are reported as such.
_Avoid_: Snapshot table, truncate-load table

**Comparison Config**:
The per-Target declaration of key columns, ignored columns and numeric tolerances, kept with the Pipeline's code.
_Avoid_: Rules, mapping

**Aggregate Check**:
The cheap first tier of comparison: row counts, sums, null rates and checksums.
_Avoid_: Summary check

**Row Diff**:
The expensive second tier of comparison, run when an Aggregate Check disagrees: rows are matched on the key columns and compared by Row Fingerprint, and full rows are examined only for a sample of the keys that differ.
_Avoid_: Full compare

**Row Fingerprint**:
A value computed from a row's non-ignored, non-tolerance columns after normalization, so two rows with equal fingerprints count as equal.
_Avoid_: Row hash, checksum (reserved for Aggregate Check)

**Keyless Target**:
A Target with no unique key, compared only as counts of identical Row Fingerprints, so it can show that rows differ but not which ones.
_Avoid_: Unkeyed table

**New Target**:
A Target with no Production counterpart yet, declared as such in its Comparison Config so it is skipped rather than reported as an error.
_Avoid_: Baseline-less target

**Missing Target**:
A Target that exists in only one Environment, for example one not yet deployed to Production. It is reported as ERROR. A Target name that exists in neither Environment is not a Missing Target but a typo, and the request naming it is rejected.
_Avoid_: Absent table, orphan

**Mismatch**:
Any difference between Staging and Production not permitted by the Comparison Config.
_Avoid_: Discrepancy, defect

**Verdict**:
The outcome of a Test Run, per Target and per Pipeline: PASS, FAIL, or ERROR when the comparison could not be completed. ERROR is never PASS. A New Target is reported as SKIPPED and does not count. A Pipeline is FAIL if any Target is FAIL, otherwise ERROR if any is ERROR, otherwise SKIPPED if every Target was skipped (nothing was verified), otherwise PASS.
_Avoid_: Result, status

**Cutover Readiness**:
The policy for deciding a Pipeline may be released, based on a history of Verdicts; distinct from any single Verdict.
_Avoid_: Go-live approval
