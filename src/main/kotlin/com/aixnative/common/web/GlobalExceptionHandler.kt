package com.aixnative.common.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
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

    /** Malformed / unreadable request body (bad JSON, wrong encoding) → client error, not 500. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Unreadable request body: {}", ex.mostSpecificCause.message)
        return ResponseEntity.badRequest().body(ApiResponse.fail("요청 본문을 읽을 수 없습니다. JSON 형식과 인코딩(UTF-8)을 확인하세요."))
    }

    /** Catch-all — never leak internal details to the client; log server-side. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("서버 오류가 발생했습니다."))
    }
}
