# 10 — Per-Pipeline bearer token authentication

**What to build:** Every API call needs a bearer token. Each token belongs to exactly one Pipeline and to its list of Targets, and can only trigger and read Test Runs for them. Tokens come from Kubernetes secrets configuration.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] A call without a token is rejected
- [ ] A call with an unknown token is rejected
- [ ] A token can trigger and read Test Runs for its own Pipeline
- [ ] A token cannot trigger a Test Run for another Pipeline, or name a Target outside its list
- [ ] A token cannot read another Pipeline's Test Runs or history
- [ ] Tokens never appear in stored results or logs
