# 06 — Numeric tolerances

**What to build:** The Comparison Config can give a numeric tolerance per column. Such columns are excluded from the Row Fingerprint and checked separately, at least by an Aggregate Check that honours the tolerance, and sampled Mismatches compare them within the tolerance. The report states how each tolerance column was checked, so nobody assumes it was fully compared row by row. This is a known gap by design (ADR 0001): a difference confined to a tolerance column is only caught if the Aggregate Check sees it.

**Blocked by:** 04 — Row Diff by Row Fingerprint (Oracle)

**Status:** ready-for-agent

- [ ] A difference within the tolerance on a tolerance column gives PASS
- [ ] A difference beyond the tolerance that shows up in the Aggregate Check gives FAIL
- [ ] A tolerance on one column does not relax any other column
- [ ] Tolerance columns are excluded from the Row Fingerprint
- [ ] The report states for each tolerance column how it was checked, and that a difference not visible in the Aggregate Check would not be caught
- [ ] A tolerance given for a column that is not numeric is rejected with a clear error
