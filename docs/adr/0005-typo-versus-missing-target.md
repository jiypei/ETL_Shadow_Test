# A Target found in neither Environment is a rejected typo; found in one, it is an ERROR

Tickets 08 and 09 pull in opposite directions: story 34 wants a request that names a table that does not exist rejected immediately, while story 27 wants a Target missing in Production, or in Staging, reported as ERROR so a missed deployment is never taken for success. A missing name looks the same either way, so the rule is decided by where it is missing:

- Found in **neither** Environment: the request is rejected (400) before any Test Run exists, as a typo.
- Found in **exactly one**: the Test Run runs and that Target is ERROR (a Missing Target). A New Target flag makes it SKIPPED instead.
- An Environment that cannot be reached at trigger time tells us nothing, so the request is accepted and the affected Targets end as ERROR.

Names are only ever placed into queries after the real metadata has confirmed them, so a hostile name reaches a query only as a bound value.

## Considered Options

- **Always reject a missing table.** Catches typos everywhere, but a Pipeline not yet deployed to Production could never produce the ERROR that ticket 08 requires, and a database outage during validation would block triggering.
- **Never reject, always ERROR.** Simplest, but a typo would create a Test Run and a stored record just to say ERROR.

## Consequences

- A typo in the name for only one Environment (say the Production table) is reported as ERROR, not as a 400, and the ERROR reason names the Environment.
- Columns named in the Comparison Config (keys, ignored, scope, tolerance) are checked against whichever Environments could be read; a column present on one side only is a schema difference, reported as FAIL, not a typo.
