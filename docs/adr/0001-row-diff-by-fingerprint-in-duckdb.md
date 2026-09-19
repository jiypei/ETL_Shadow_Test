# Row Diff compares Row Fingerprints in an embedded DuckDB, not full rows

The largest Target is about 20 million rows and 100GB, so loading both Environments' full rows into DuckDB would move about 200GB out of Oracle on every Test Run. Instead the Row Diff runs in two phases: key plus Row Fingerprint is computed where the data lives (in Oracle for Oracle Targets, in DuckDB directly over MinIO for Parquet Targets) and diffed in DuckDB, then full rows are fetched only for a sample of the keys that differ. Both sides of a comparison are always the same Target type, so the fingerprint function is the same on both sides.

## Considered Options

- **Load full rows into DuckDB and diff there.** Simplest and gives complete row detail, but the extraction cost and temp disk (hundreds of GB per Test Run) are prohibitive.
- **Push the whole diff down into each Oracle via key-range bucket hashes.** Avoids DuckDB, but the two Oracle instances cannot be joined, and DuckDB was already available for Parquet.

## Consequences

- Columns with a numeric tolerance are excluded from the Row Fingerprint and checked separately by Aggregate Check, so a large difference in a tolerance column can go unseen when the fingerprints match. Reports must state how tolerance columns were checked.
- Fingerprint normalization must follow the comparison rules in the Comparison Config: exact DECIMAL, NULL equal to NULL, strings untrimmed.
- DuckDB memory is off-heap, so the container memory limit must account for it, and the service caps concurrent Test Runs.
