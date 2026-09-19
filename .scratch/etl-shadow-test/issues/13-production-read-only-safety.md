# 13 — Production read-only safety

**What to build:** The service reaches Production only through a read-only account and never needs write access to it. When a Production read replica is configured, comparison queries go to it. Connections, parallelism and query time against Production are limited by configuration so a comparison cannot degrade it.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] In the test environment the Production account has read-only rights, and every Shadow Test still works
- [ ] Any write attempted against Production fails, and no comparison depends on one
- [ ] When a read replica is configured, Production queries go to it and not to the primary
- [ ] The maximum number of connections to Production is configurable and respected
- [ ] The parallelism of Production queries is configurable and respected
- [ ] A Production query that exceeds its time limit ends and gives ERROR for the affected Target
