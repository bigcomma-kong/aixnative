package com.aixnative.common.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Expected, typed API errors → mapped to their declared HTTP status. */
    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ex.status).body(ApiResponse.fail(ex.message ?: ex.status.reasonPhrase))

    /** Bean-validation failures on @Valid request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val msg = ex.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
            .ifBlank { "잘못된 요청입니다." }
        return ResponseEntity.badRequest().body(ApiResponse.fail(msg))
    }

    /** Catch-all — never leak internal details to the client; log server-side. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("서버 오류가 발생했습니다."))
    }
}
