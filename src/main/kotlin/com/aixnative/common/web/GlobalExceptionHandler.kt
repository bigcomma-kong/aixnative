package com.aixnative.common.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException

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

    /**
     * `require(...)` 위반 → 400. 서비스가 던지는 IllegalArgumentException 메시지는 사용자에게 보여줄
     * 목적으로 쓰여 있으므로(예: "스토리(STORY) 게시물만 이미지 재생성이 가능합니다.") 그대로 전달한다.
     * 내부 상태 결함인 IllegalStateException 은 의도적으로 제외 - catch-all 이 500 으로 감춘다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Bad request: {}", ex.message)
        return ResponseEntity.badRequest().body(ApiResponse.fail(ex.message ?: "잘못된 요청입니다."))
    }

    /**
     * 업로드 용량 초과 → 413. 핸들러가 없으면 catch-all 이 "서버 오류가 발생했습니다" 500 으로 감춰
     * 사용자가 원인(파일이 큼)을 알 수 없다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(ex: MaxUploadSizeExceededException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Upload too large: {}", ex.message)
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ApiResponse.fail("파일이 너무 큽니다. 업로드 한도를 확인해 주세요."))
    }

    /** multipart 요청 자체가 깨진 경우(경계 누락·잘린 전송 등) → 400. */
    @ExceptionHandler(MultipartException::class)
    fun handleMultipart(ex: MultipartException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Malformed multipart request: {}", ex.message)
        return ResponseEntity.badRequest().body(ApiResponse.fail("파일 업로드 요청을 읽을 수 없습니다. 다시 시도해 주세요."))
    }

    /** Catch-all — never leak internal details to the client; log server-side. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("서버 오류가 발생했습니다."))
    }
}
