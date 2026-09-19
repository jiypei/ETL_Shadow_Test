# 14 — Verdict history query

**What to build:** A caller can list a Pipeline's past Test Runs, each with its scope, Verdicts and time, so a release manager can judge readiness. The service answers by having DuckDB scan the Test Run records in MinIO. Cutover Readiness itself is not part of this ticket. See ADR 0002.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] The history for a Pipeline lists its Test Runs with scope, Pipeline Verdict, per-Target Verdicts and time
- [ ] Runs are ordered with the most recent first
- [ ] The history can be limited to a time range
- [ ] Abandoned and ERROR Test Runs appear in the history
- [ ] A Pipeline with no Test Runs returns an empty history, not an error
- [ ] The history of one Pipeline never includes another Pipeline's Test Runs
