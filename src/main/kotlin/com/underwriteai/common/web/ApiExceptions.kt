package com.underwriteai.common.web

import org.springframework.http.HttpStatus

/**
 * Base type for expected, user-facing API errors. Each carries the HTTP status
 * the [GlobalExceptionHandler] should map it to.
 */
sealed class ApiException(
    val status: HttpStatus,
    message: String,
) : RuntimeException(message)

/** 400 — invalid input / bad request. */
class BadRequestException(message: String) : ApiException(HttpStatus.BAD_REQUEST, message)

/** 401 — authentication missing or invalid. */
class UnauthorizedException(message: String = "인증이 필요합니다.") :
    ApiException(HttpStatus.UNAUTHORIZED, message)

/** 403 — authenticated but not allowed (e.g. cross-tenant access). */
class ForbiddenException(message: String = "접근 권한이 없습니다.") :
    ApiException(HttpStatus.FORBIDDEN, message)

/** 404 — resource not found (scoped to the current tenant). */
class NotFoundException(message: String = "대상을 찾을 수 없습니다.") :
    ApiException(HttpStatus.NOT_FOUND, message)

/** 409 — conflict, e.g. duplicate signup email. */
class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, message)

/**
 * 402 — Payment Required. Raised by the credit gate when the tenant has no
 * remaining AI-analysis credits. The client renders the paywall.
 */
class InsufficientCreditsException(message: String = "남은 크레딧이 없습니다. 크레딧을 구매해 주세요.") :
    ApiException(HttpStatus.PAYMENT_REQUIRED, message)
