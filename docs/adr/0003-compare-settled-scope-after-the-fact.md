# Shadow Test compares settled data after both runs finish, without controlling inputs

Staging and Production run the Pipeline independently, at different times, against their own source Oracle, and the Shadow Test is called only after the Staging run completes. We do not freeze or snapshot the sources to make inputs identical; instead each Test Run is limited to a Comparison Scope of data that is no longer changing, taken from a scope column declared in the Comparison Config. This keeps the Pipelines untouched and needs no extra source infrastructure.

## Consequences

- A Full-Refresh Target has no scope column and is compared whole, so its Mismatches may come from source drift rather than code changes. Reports flag them as such.
- A Mismatch is therefore evidence, not proof, of a code difference; triage must consider whether the Comparison Scope was actually settled.
