package com.etlshadowtest.duckdb

import com.etlshadowtest.config.MinioProperties
import java.net.URI
import java.sql.Connection

object DuckS3 {
    /**
     * Makes s3:// paths in the bucket of [minio] readable on [connection]. The secret is temporary (it lives only as long as
     * the connection) and scoped to that bucket, so several Environments can be configured on one connection.
     */
    fun configure(connection: Connection, secretName: String, minio: MinioProperties, extensionDirectory: String?) {
        val uri = URI(minio.endpoint)
        val endpoint = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
        connection.createStatement().use { s ->
            if (extensionDirectory != null) s.execute("SET extension_directory = ${Workspace.literal(extensionDirectory)}")
            s.execute("INSTALL httpfs")
            s.execute("LOAD httpfs")
            s.execute(
                "CREATE OR REPLACE TEMPORARY SECRET $secretName (TYPE S3, KEY_ID ${Workspace.literal(minio.accessKey)}, " +
                    "SECRET ${Workspace.literal(minio.secretKey)}, ENDPOINT ${Workspace.literal(endpoint)}, URL_STYLE 'path', " +
                    "USE_SSL ${uri.scheme == "https"}, SCOPE ${Workspace.literal("s3://${minio.bucket}")})",
            )
        }
    }
}
