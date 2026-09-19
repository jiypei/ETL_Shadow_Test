package com.etlshadowtest.oracle

import com.etlshadowtest.api.Location
import com.etlshadowtest.config.OracleProperties
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/** Oracle access for one Environment. */
class OracleSide(val environment: String, props: OracleProperties) : AutoCloseable {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            poolName = "oracle-$environment"
            jdbcUrl = props.url
            username = props.username
            password = props.password
            maximumPoolSize = props.maxConnections
            minimumIdle = 0
            initializationFailTimeout = -1
        },
    )

    fun rowCount(location: Location): Long = pool.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM ${qualified(location)}").use { rs ->
                rs.next()
                rs.getBigDecimal(1).longValueExact()
            }
        }
    }

    override fun close() = pool.close()

    companion object {
        fun quote(identifier: String): String {
            require(identifier.isNotEmpty() && '"' !in identifier && '\u0000' !in identifier) { "Invalid identifier: $identifier" }
            return "\"$identifier\""
        }

        fun qualified(location: Location) = "${quote(location.schema!!)}.${quote(location.table!!)}"
    }
}
