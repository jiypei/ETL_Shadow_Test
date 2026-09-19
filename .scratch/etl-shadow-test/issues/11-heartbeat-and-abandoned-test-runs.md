# 11 — Heartbeat and abandoned Test Runs

**What to build:** While a Test Run executes, the service keeps updating a heartbeat in the Test Run record. If the service instance dies mid-run, the record would stay in progress forever, so polling reports a Test Run whose heartbeat has gone stale as ERROR with the reason abandoned. The caller can trigger again, which creates a new Test Run. The staleness threshold is configurable. See ADR 0002.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] A running Test Run's record shows a heartbeat that keeps advancing
- [ ] A Test Run record left in progress with a stale heartbeat, written directly into MinIO by the test, is reported as ERROR with the reason abandoned
- [ ] A Test Run that is still running and heartbeating is never reported as abandoned
- [ ] Triggering the same Pipeline again after an abandoned Test Run creates a new Test Run and does not resume the old one
- [ ] The staleness threshold is configurable
