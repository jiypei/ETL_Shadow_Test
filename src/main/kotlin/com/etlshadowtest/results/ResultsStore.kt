package com.etlshadowtest.results

import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.run.RunRecord
import com.fasterxml.jackson.databind.ObjectMapper
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.errors.ErrorResponseException
import org.springframework.stereotype.Component

/** Test Run results in MinIO (ADR 0002). Status changes overwrite the single run.json, which is atomic per object. */
@Component
class ResultsStore(props: ShadowProperties, private val mapper: ObjectMapper) {
    private val bucket = props.results.minio.bucket
    private val client: MinioClient = MinioClient.builder()
        .endpoint(props.results.minio.endpoint)
        .credentials(props.results.minio.accessKey, props.results.minio.secretKey)
        .build()

    fun runKey(pipeline: String, testRunId: String) = "results/$pipeline/$testRunId/run.json"

    fun write(record: RunRecord) {
        ensureBucket()
        val bytes = mapper.writeValueAsBytes(record)
        client.putObject(
            PutObjectArgs.builder().bucket(bucket).`object`(runKey(record.pipeline, record.testRunId))
                .stream(bytes.inputStream(), bytes.size.toLong(), -1).contentType("application/json").build(),
        )
    }

    fun mismatchesKey(pipeline: String, testRunId: String, target: String) = "results/$pipeline/$testRunId/mismatches/$target.parquet"

    /** Uploads a Target's sampled Mismatches and returns their object key. */
    fun writeMismatches(pipeline: String, testRunId: String, target: String, file: java.nio.file.Path): String {
        ensureBucket()
        val key = mismatchesKey(pipeline, testRunId, target)
        client.uploadObject(io.minio.UploadObjectArgs.builder().bucket(bucket).`object`(key).filename(file.toString()).build())
        return key
    }

    fun read(pipeline: String, testRunId: String): RunRecord? = try {
        client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(runKey(pipeline, testRunId)).build())
            .use { mapper.readValue(it, RunRecord::class.java) }
    } catch (e: ErrorResponseException) {
        if (e.errorResponse().code() == "NoSuchKey" || e.errorResponse().code() == "NoSuchBucket") null else throw e
    }

    @Volatile
    private var bucketChecked = false

    private fun ensureBucket() {
        if (bucketChecked) return
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
        }
        bucketChecked = true
    }
}
