package com.etlshadowtest.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.errors.ErrorResponseException

/** Reads and writes the shared MinIO the way the tests need: results inspection and stale-record setup. */
object MinioFixtures {
    private val mapper = ObjectMapper()

    val client: MinioClient by lazy {
        MinioClient.builder()
            .endpoint(TestEnvironment.minio.s3URL)
            .credentials(TestEnvironment.minio.userName, TestEnvironment.minio.password)
            .build()
    }

    fun createBuckets(vararg names: String) {
        for (name in names) {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(name).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(name).build())
            }
        }
    }

    fun runJsonKey(pipeline: String, testRunId: String) = "results/$pipeline/$testRunId/run.json"

    fun readText(bucket: String, key: String): String =
        client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key).build()).use { String(it.readAllBytes()) }

    fun readRunJson(pipeline: String, testRunId: String): JsonNode =
        mapper.readTree(readText(TestEnvironment.RESULTS_BUCKET, runJsonKey(pipeline, testRunId)))

    fun rawRunJson(pipeline: String, testRunId: String): String =
        readText(TestEnvironment.RESULTS_BUCKET, runJsonKey(pipeline, testRunId))

    fun writeText(bucket: String, key: String, text: String) {
        val bytes = text.toByteArray()
        client.putObject(
            PutObjectArgs.builder().bucket(bucket).`object`(key)
                .stream(bytes.inputStream(), bytes.size.toLong(), -1).contentType("application/json").build(),
        )
    }

    fun writeRunJson(pipeline: String, testRunId: String, json: JsonNode) =
        writeText(TestEnvironment.RESULTS_BUCKET, runJsonKey(pipeline, testRunId), mapper.writeValueAsString(json))

    fun exists(bucket: String, key: String): Boolean = try {
        client.statObject(io.minio.StatObjectArgs.builder().bucket(bucket).`object`(key).build()); true
    } catch (e: ErrorResponseException) {
        false
    }

    fun list(bucket: String, prefix: String): List<String> =
        client.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())
            .map { it.get().objectName() }
}
