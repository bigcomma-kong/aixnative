package com.aixnative.common.web

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
 * 402 — Payment Required. Raised by the credit gate when the tenant can't cover
 * the analysis cost. The client renders the paywall.
 */
class InsufficientCreditsException(message: String = "남은 크레딧이 없습니다. 크레딧을 구매해 주세요.") :
    ApiException(HttpStatus.PAYMENT_REQUIRED, message) {
    companion object {
        /** 차등 과금용 — 필요 크레딧과 현재 잔액을 안내한다. */
        fun forRequirement(required: Int, balance: Int) = InsufficientCreditsException(
            "이 분석에는 ${required}크레딧이 필요합니다(현재 잔액 ${balance}). 크레딧을 충전해 주세요.",
        )
    }
}

/** 429 — too many requests (e.g. signup flood from one IP). */
class TooManyRequestsException(message: String = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.") :
    ApiException(HttpStatus.TOO_MANY_REQUESTS, message)

/** 503 — a downstream dependency (e.g. the AI provider) is not configured/available. */
class ServiceUnavailableException(message: String) : ApiException(HttpStatus.SERVICE_UNAVAILABLE, message)
