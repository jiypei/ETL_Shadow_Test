# 09 — Comparison Config validation

**What to build:** Before any comparison starts, the service checks every table, column and key named in the Comparison Config against the real Oracle metadata or Parquet schema. Names are only ever placed into queries as quoted identifiers, and the request accepts no raw SQL fragment anywhere. An invalid request is rejected up front with a clear error and starts no Test Run.

**Blocked by:** 01 — Walking skeleton: trigger and poll a row-count Shadow Test

**Status:** ready-for-agent

- [ ] A table that does not exist in the named Environment is rejected with an error naming it
- [ ] A column, key column, scope column or ignored column that does not exist is rejected with an error naming it
- [ ] Names containing quotes, semicolons, comments or SQL keywords are rejected or safely quoted, and never change what runs
- [ ] A value where a name is expected, such as a subquery, is rejected
- [ ] A rejected request does not create a Test Run
- [ ] Valid requests are unaffected
