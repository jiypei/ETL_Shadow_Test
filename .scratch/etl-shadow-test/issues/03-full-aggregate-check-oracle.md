# 03 — Full Aggregate Check for Oracle Targets

**What to build:** The Aggregate Check becomes complete for Oracle Targets: row counts, sums of numeric columns, null rates per column, and an order-independent checksum over the row content, all computed inside Oracle within the Comparison Scope. Ignored columns from the Comparison Config are left out. The comparison rules apply: Oracle numbers compared as exact decimals, NULL equal to NULL, strings not trimmed, dates and timestamps compared as timestamps. The report shows the value from each Environment for every check. A disagreement in any check gives FAIL.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] Identical data gives PASS
- [ ] A difference in row count, a numeric sum, a column's null rate, or a value that changes only the checksum each gives FAIL, and the report shows which check disagreed with both values
- [ ] A difference confined to an ignored column gives PASS
- [ ] Decimal values that differ only beyond float precision are still detected as different
- [ ] A NULL on one side and a NULL on the other are equal; a NULL against an empty-looking value is not
- [ ] Strings that differ only by trailing spaces are detected as different
- [ ] A date that differs only in its time component is detected as different
