package com.etlshadowtest.target

/**
 * What the shared query shapes need from a database's SQL dialect. Each adapter has one. The expressions differ per
 * database, and only need to agree with themselves: both sides of a comparison are always the same Target type (ADR 0001).
 */
interface SqlDialect {
    /** Quotes an identifier. Names are only ever placed in queries after the real metadata has confirmed them. */
    fun quote(identifier: String): String

    /** The column's value as text in the form that equality is decided on (exact decimals, untrimmed strings, timestamps). */
    fun canonical(column: ColumnMeta): String

    /** The sum of a column; for floating-point columns this is only informational (see [ColumnMeta.floatingPoint]). */
    fun sum(column: ColumnMeta): String

    /** An expression giving the Row Fingerprint of the given columns. */
    fun rowFingerprint(columns: List<ColumnMeta>): String

    /** An order-independent checksum over the Row Fingerprints of the given columns. */
    fun checksum(columns: List<ColumnMeta>): String
}
