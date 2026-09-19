# 02 — Comparison Scope and Full-Refresh Targets

**What to build:** Comparison is limited to settled data. The Comparison Config declares a scope column for each Target, and the trigger supplies the range to compare. A Target declared as a Full-Refresh Target has no scope column and is compared whole; if it fails, the report flags that the Mismatch may come from source drift rather than a code change.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] Rows outside the requested range are ignored on both sides: differing rows outside the range still give PASS
- [ ] Rows inside the range that differ give FAIL
- [ ] A Full-Refresh Target is compared whole, whatever range the trigger supplies
- [ ] A failing Full-Refresh Target is reported with a note that source drift may be the cause
- [ ] A Target that is not Full-Refresh, or a trigger without the range that such a Target needs, is rejected with a clear error
- [ ] The scope used is stored in the Test Run record
