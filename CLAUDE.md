# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Implemented: tickets 01 to 15 in `.scratch/etl-shadow-test/issues/` (Kotlin 2.2, Spring Boot 3.5, JDK 21, Gradle). API and configuration are in `docs/operations.md`.

## Commands

- Needs JDK 21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21` on this machine) and a running Docker: tests start two Oracle Free containers (`gvenzl/oracle-free:23-slim-faststart`) and one MinIO (`quay.io/minio/minio`) through Testcontainers. Pull these images first; Docker Hub no longer serves `minio/minio`.
- All tests: `gradle test`. One class: `gradle test --tests '*RowDiffTest'`. There is no separate lint step.
- The seam is the public API: tests trigger and poll Test Runs through HTTP and assert on Verdicts, reports and MinIO contents. Do not test internal classes directly.
- Tests that must change a connection or limit extend `ShadowTestSupport` and register their own properties via `TestEnvironment.registerProperties(registry, overrides)`. Extend `ShadowTestBase` otherwise. Two `@DynamicPropertySource` methods in one hierarchy do not override each other reliably.
- If MinIO returns `RequestTimeTooSkewed`, the Docker VM clock has drifted (after sleep). Reset it with a privileged container: `docker run --rm --privileged --entrypoint sh <local image> -c "date -u -s @$(date -u +%s)"`.

## Layout

`api` (controller, request/error types), `auth` (bearer tokens), `validation` (request checks against real metadata), `run` (Test Run service, record, heartbeat, sweep), `compare` (per-Target comparison, Aggregate Check, Row Diff), `target`/`oracle`/`parquet` (`TargetSide` implementations and SQL builders), `duckdb` (per-run workspace), `results` (MinIO run records, history), `webhook`.

Read these before designing or implementing anything:
- `CONTEXT.md`: the glossary. Use its terms exactly (Target, Test Run, Comparison Scope, Comparison Config, Aggregate Check, Row Diff, Row Fingerprint, Verdict, ...) and respect the `_Avoid_` words. Keep it a glossary only, with no implementation detail.
- `docs/adr/`: decisions that look wrong without context. Do not "fix" them without superseding the ADR.

## What this is

A Kotlin/Spring Boot service, deployed on Kubernetes, that verifies an ETL Pipeline before release. After a Pipeline finishes in Staging, the caller triggers a Shadow Test, and the service compares the Staging Targets with the Production Targets. The Pipelines themselves live in other repos; this repo is only the comparison harness. Targets are Oracle tables or Parquet datasets in MinIO.

## Agreed design

- **Trigger:** an authenticated API call carrying the Pipeline name, the Comparison Scope and the Comparison Config (config lives in each Pipeline's repo and is sent in the request, not registered ahead of time). It returns a Test Run ID immediately; callers poll for the Verdict, with an optional webhook callback.
- **Two-tier comparison:** an Aggregate Check first (SQL in Oracle for Oracle Targets, DuckDB over MinIO for Parquet Targets). Only if it disagrees does the Row Diff run: key plus Row Fingerprint diffed in an embedded DuckDB, then full rows fetched for a sample of differing keys. See ADR 0001.
- **Comparison rules:** exact by default. Oracle `NUMBER` compares as DECIMAL, NULL equals NULL, strings are not trimmed. Any exception (ignored columns, numeric tolerances) must be in the Comparison Config, and there are no implicit conversions.
- **Verdicts:** PASS, FAIL, ERROR (comparison could not complete, never treated as PASS), and SKIPPED for a New Target. Duplicate keys are a FAIL; a Target with no unique key must be declared keyless.
- **Results:** stored in MinIO (`results/{pipeline}/{testRunId}/run.json` plus sampled `mismatches/{target}.parquet`), with history queried through DuckDB. See ADR 0002.
- **Scope:** the comparison covers only settled data and does not control input parity between Environments. See ADR 0003.

## Constraints that span the code

- Production is accessed only with a read-only account, and the service must never need write access to it. Prefer a read replica for Production, else run off-peak, and cap connections, parallelism and query timeouts.
- Table and column names from a Comparison Config must be validated against the real Oracle metadata or Parquet schema before being quoted as identifiers into SQL. Never accept raw SQL fragments. Each caller token maps to one Pipeline and its Target list.
- DuckDB memory is off-heap, so set `memory_limit` and `temp_directory`, account for it in the container memory limit, and cap concurrent Test Runs.
- Run a single replica with the `Recreate` deployment strategy in the first version. A Test Run whose heartbeat in `run.json` goes stale is reported as ERROR (abandoned).
- Tolerance columns are excluded from the Row Fingerprint and checked separately, so reports must say how they were checked.
- Cutover Readiness is out of scope for now; only a query API for a Pipeline's Verdict history is planned.

## Open facts

Not yet confirmed: whether the largest Target (about 20M rows, 100GB) has LOB columns, and whether a Production read replica or an off-peak window exists.

## Agent skills

### Issue tracker

Issues and specs are local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
