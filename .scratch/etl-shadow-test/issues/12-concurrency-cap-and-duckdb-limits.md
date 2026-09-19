# 12 — Concurrency cap and DuckDB resource limits

**What to build:** The number of Test Runs executing at once is capped, and DuckDB's memory limit and temporary directory are configurable, because DuckDB memory sits outside the JVM heap and must fit within the container limit. The behaviour when a trigger arrives at the cap, either queueing it or rejecting it, is decided while doing this ticket, recorded, and made visible to callers. See ADR 0001.

**Blocked by:** 04 — Row Diff by Row Fingerprint (Oracle)

**Status:** ready-for-agent

- [ ] No more than the configured number of Test Runs execute at the same time
- [ ] A trigger that arrives at the cap behaves as documented (queued or rejected), and the caller can tell which happened
- [ ] The DuckDB memory limit and temporary directory are configurable
- [ ] A Row Diff that exceeds the memory limit spills to the temporary directory instead of failing
- [ ] Temporary DuckDB files are removed when a Test Run ends, including when it fails
- [ ] The chosen cap behaviour is recorded in the docs
