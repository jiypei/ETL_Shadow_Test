package com.etlshadowtest.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: HttpStatus, message: String, val details: List<String> = emptyList()) : RuntimeException(message)

data class ErrorBody(val error: String, val details: List<String> = emptyList())

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(ErrorBody(e.message ?: e.status.reasonPhrase, e.details))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.badRequest().body(ErrorBody("Malformed request: ${e.mostSpecificCause.message?.lineSequence()?.first()}"))
}
