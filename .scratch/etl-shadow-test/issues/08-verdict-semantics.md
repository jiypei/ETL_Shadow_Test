# 08 — Verdict semantics: multiple Targets, ERROR, SKIPPED

**What to build:** One Test Run can cover several Targets, each with its own Verdict, and the Pipeline gets one Verdict: FAIL if any Target is FAIL, otherwise ERROR if any is ERROR, otherwise PASS. A comparison that could not complete, such as a connection failure, a query timeout, or a Target missing on either side, is ERROR and never PASS. A Target declared as a New Target, with no Production counterpart yet, is SKIPPED, does not count toward the Pipeline Verdict, and is listed in the report as unverified.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] A Test Run over several Targets reports a Verdict per Target
- [ ] The Pipeline Verdict is FAIL when any Target fails, even if another is ERROR
- [ ] The Pipeline Verdict is ERROR when no Target fails and at least one is ERROR
- [ ] The Pipeline Verdict is PASS only when every counted Target passes
- [ ] A Target missing in Production, without the New Target flag, is ERROR
- [ ] A Target missing in Staging is ERROR
- [ ] A source database that cannot be reached gives ERROR for the affected Targets, and the other Targets still complete
- [ ] A New Target is SKIPPED, does not affect the Pipeline Verdict, and is clearly listed as unverified
- [ ] A Pipeline whose Targets are all SKIPPED does not report PASS
