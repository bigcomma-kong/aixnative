package com.underwriteai.common.web

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Consistent response envelope for every API endpoint.
 * (Project convention: success indicator + data payload + error message + optional meta.)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val meta: Map<String, Any?>? = null,
) {
    companion object {
        fun <T> ok(data: T, meta: Map<String, Any?>? = null): ApiResponse<T> =
            ApiResponse(success = true, data = data, meta = meta)

        fun <T> fail(error: String): ApiResponse<T> =
            ApiResponse(success = false, error = error)
    }
}
