# 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**What to build:** A Kotlin/Spring Boot service, plus the Testcontainers test environment that later tickets reuse (two Oracle instances standing in for Staging and Production, and one MinIO). A caller triggers a Shadow Test for a Pipeline with a Comparison Config that names one Oracle Target. The Target has a logical name, its type declared once (Oracle) and applying to both Environments, a Staging location and a Production location, each written as schema and table. The service returns a Test Run ID immediately, compares the row counts of the two tables, and the caller polls until it gets a Verdict: PASS if the counts match, FAIL if not. The Test Run record is written to MinIO. Environment connections and credentials come from service configuration, never from the request.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] The test suite starts real Oracle (two instances) and MinIO containers and runs the service against them
- [ ] Triggering returns a Test Run ID before the comparison has finished
- [ ] Polling shows an in-progress state, then the final Verdict
- [ ] Equal row counts give PASS; different row counts give FAIL
- [ ] The Test Run record in MinIO holds the Verdict, the counts from both sides, and the Comparison Config that was used
- [ ] A request that tries to supply connection details or credentials is rejected
- [ ] Reusable test helpers exist for creating Target data in both Oracle instances and for reading results from MinIO
