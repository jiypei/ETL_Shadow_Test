# 15 — Webhook callback

**What to build:** A trigger can include an optional callback address. When the Test Run finishes, the service sends the Test Run ID and the Pipeline Verdict to it, so callers do not have to poll. What happens when the callback fails, whether it is retried and how often, is decided while doing this ticket and recorded.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] A trigger with a callback address causes one callback when the Test Run finishes, carrying the Test Run ID and the Pipeline Verdict
- [ ] A trigger without a callback address behaves as before
- [ ] A failing callback never changes the Test Run's Verdict, and the failure is recorded in the Test Run record
- [ ] The retry behaviour for a failing callback is as documented
- [ ] ERROR and abandoned Test Runs also trigger the callback
