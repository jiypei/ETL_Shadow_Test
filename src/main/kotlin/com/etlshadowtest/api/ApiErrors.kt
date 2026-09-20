package com.etlshadowtest.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(
    val status: HttpStatus,
    message: String,
    val details: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message)

data class ErrorBody(val error: String, val details: List<String> = emptyList())

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ErrorBody> {
        val headers = HttpHeaders().apply { e.headers.forEach { (name, value) -> set(name, value) } }
        return ResponseEntity.status(e.status).headers(headers).body(ErrorBody(e.message ?: e.status.reasonPhrase, e.details))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.badRequest().body(ErrorBody("Malformed request: ${e.mostSpecificCause.message?.lineSequence()?.first()}"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun badParameter(e: MethodArgumentTypeMismatchException) = ResponseEntity.badRequest().body(ErrorBody("Invalid value for '${e.name}'"))
}
