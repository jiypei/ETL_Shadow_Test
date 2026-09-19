package com.etlshadowtest.target

import com.etlshadowtest.api.TargetType
import com.etlshadowtest.oracle.OracleSides
import com.etlshadowtest.parquet.ParquetSides
import org.springframework.stereotype.Component

/** Both sides of a Target are always of the same type, so the side is picked by the Target's declared type. */
@Component
class TargetSides(private val oracle: OracleSides, private val parquet: ParquetSides) {
    fun staging(type: TargetType): TargetSide = when (type) {
        TargetType.ORACLE -> oracle.staging
        TargetType.PARQUET -> parquet.staging
    }

    fun production(type: TargetType): TargetSide = when (type) {
        TargetType.ORACLE -> oracle.production
        TargetType.PARQUET -> parquet.production
    }
}
