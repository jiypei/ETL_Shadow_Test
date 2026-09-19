# 05 — Duplicate keys and Keyless Targets

**What to build:** A Target's key columns must identify rows uniquely on both sides. A duplicated key on either side is itself a Mismatch and gives FAIL. A Target with no unique key is declared keyless in the Comparison Config; it is compared by counting identical Row Fingerprints, so the report can show that rows differ but not which ones.

**Blocked by:** 04 — Row Diff by Row Fingerprint (Oracle)

**Status:** ready-for-agent

- [ ] Duplicate keys on the Staging side give FAIL, with the duplicated keys reported
- [ ] Duplicate keys on the Production side give FAIL, with the duplicated keys reported
- [ ] A keyless Target with identical data gives PASS
- [ ] A keyless Target where a row's content differs gives FAIL, and the report shows count differences of identical rows
- [ ] A keyless Target's report does not claim to identify individual rows, and says why
- [ ] A Target that is neither keyless nor given key columns is rejected with a clear error
