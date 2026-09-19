package com.etlshadowtest.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * Real external systems shared by every test class in the JVM: two Oracle instances standing in
 * for Staging and Production, and one MinIO that holds Parquet Targets and the results store.
 */
object TestEnvironment {
    const val ORACLE_IMAGE = "gvenzl/oracle-free:23-slim-faststart"
    const val MINIO_IMAGE = "quay.io/minio/minio:latest"
    const val STAGING_BUCKET = "staging-data"
    const val PRODUCTION_BUCKET = "production-data"
    const val RESULTS_BUCKET = "shadow-results"
    const val SYSTEM_PASSWORD = "test"

    const val STAGING_OWNER = "STG_OWNER"
    const val STAGING_READER = "STG_READER"
    const val PRODUCTION_OWNER = "PROD_OWNER"
    const val PRODUCTION_READER = "PROD_READER"
    const val ACCOUNT_PASSWORD = "Reader_pw_1"

    private val stagingContainer = OracleContainer(ORACLE_IMAGE).withPassword(SYSTEM_PASSWORD)
    private val productionContainer = OracleContainer(ORACLE_IMAGE).withPassword(SYSTEM_PASSWORD)
    val minio: MinIOContainer = MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"))

    val staging: OracleDb
    val production: OracleDb

    init {
        Startables.deepStart(listOf(stagingContainer, productionContainer, minio)).join()
        check(stagingContainer.isRunning && productionContainer.isRunning && minio.isRunning) {
            "Test containers failed to start"
        }
        staging = OracleDb(stagingContainer, STAGING_OWNER, STAGING_READER)
        production = OracleDb(productionContainer, PRODUCTION_OWNER, PRODUCTION_READER)
        MinioFixtures.createBuckets(STAGING_BUCKET, PRODUCTION_BUCKET, RESULTS_BUCKET)
    }

    /** Where the service keeps its per-Test-Run DuckDB working space, so tests can check it is cleaned up. */
    val duckdbTempDirectory: java.nio.file.Path = java.nio.file.Files.createTempDirectory("shadow-duckdb")

    /** Registers the connections of the real test systems; [overrides] replace individual properties. */
    fun registerProperties(registry: DynamicPropertyRegistry, overrides: Map<String, String> = emptyMap()) {
        val properties = LinkedHashMap<String, () -> Any>()
        registerAll { name, supplier -> properties[name] = { supplier.get() } }
        overrides.forEach { (name, value) -> properties[name] = { value } }
        properties.forEach { (name, supplier) -> registry.add(name, supplier) }
    }

    private fun registerAll(registry: DynamicPropertyRegistry) {
        registry.add("shadow.duckdb.temp-directory") { duckdbTempDirectory.toString() }
        registry.add("shadow.staging.oracle.url") { staging.jdbcUrl }
        registry.add("shadow.staging.oracle.username") { STAGING_READER }
        registry.add("shadow.staging.oracle.password") { ACCOUNT_PASSWORD }
        registry.add("shadow.production.oracle.url") { production.jdbcUrl }
        registry.add("shadow.production.oracle.username") { PRODUCTION_READER }
        registry.add("shadow.production.oracle.password") { ACCOUNT_PASSWORD }
        for ((env, bucket) in listOf("staging" to STAGING_BUCKET, "production" to PRODUCTION_BUCKET, "results" to RESULTS_BUCKET)) {
            registry.add("shadow.$env.minio.endpoint") { minio.s3URL }
            registry.add("shadow.$env.minio.access-key") { minio.userName }
            registry.add("shadow.$env.minio.secret-key") { minio.password }
            registry.add("shadow.$env.minio.bucket") { bucket }
        }
    }
}

/** One Oracle instance with an owner schema (writes test data) and a reader account (what the service uses). */
class OracleDb(container: OracleContainer, val owner: String, val reader: String) {
    val jdbcUrl: String = "jdbc:oracle:thin:@//${container.host}:${container.oraclePort}/${container.databaseName}"

    init {
        connect("system", TestEnvironment.SYSTEM_PASSWORD).use { c ->
            c.createStatement().use { s ->
                for (user in listOf(owner, reader)) {
                    s.execute("CREATE USER $user IDENTIFIED BY ${TestEnvironment.ACCOUNT_PASSWORD} QUOTA UNLIMITED ON USERS")
                    s.execute("GRANT CREATE SESSION TO $user")
                }
                s.execute("GRANT CREATE TABLE, CREATE VIEW, CREATE PROCEDURE TO $owner")
            }
        }
    }

    fun connect(user: String, password: String): Connection = DriverManager.getConnection(jdbcUrl, user, password)

    fun ownerConnection(): Connection = connect(owner, TestEnvironment.ACCOUNT_PASSWORD)

    fun readerConnection(): Connection = connect(reader, TestEnvironment.ACCOUNT_PASSWORD)

    fun systemExecute(sql: String) = connect("system", TestEnvironment.SYSTEM_PASSWORD).use { it.createStatement().use { s -> s.execute(sql) } }

    /** For grants on SYS-owned objects, which even SYSTEM may not make. */
    fun sysdbaExecute(sql: String) = connect("sys as sysdba", TestEnvironment.SYSTEM_PASSWORD).use { it.createStatement().use { s -> s.execute(sql) } }

    /** Creates an owner table (or view via [ddl] override) and lets the reader account select from it. */
    fun createTable(name: String, columns: String) {
        ownerConnection().use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE $name ($columns)")
                s.execute("GRANT SELECT ON $name TO $reader")
            }
        }
    }

    fun execute(sql: String) = ownerConnection().use { it.createStatement().use { s -> s.execute(sql) } }

    fun grantSelect(objectName: String) = execute("GRANT SELECT ON $objectName TO $reader")

    fun insert(table: String, columns: List<String>, vararg rows: List<Any?>) {
        ownerConnection().use { c ->
            val sql = "INSERT INTO $table (${columns.joinToString(",") { "\"$it\"" }}) VALUES (${columns.joinToString(",") { "?" }})"
            c.prepareStatement(sql).use { ps ->
                for (row in rows) {
                    row.forEachIndexed { i, v -> if (v == null) ps.setObject(i + 1, null) else ps.setObject(i + 1, v) }
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun update(sql: String) = ownerConnection().use { c -> c.createStatement().use { it.executeUpdate(sql) } }
}
