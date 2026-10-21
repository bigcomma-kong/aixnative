package com.aixnative.tenant.web

import com.aixnative.billing.service.RequiresCredit
import com.aixnative.common.web.ApiResponse
import com.aixnative.integration.dart.domain.DartCorp
import com.aixnative.tenant.service.TenantCreditRequest
import com.aixnative.tenant.service.TenantCreditResponse
import com.aixnative.tenant.service.TenantCreditService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 임차인 신용·지급능력(DART). 검색은 무료, 분석은 과금(성공 시에만 차감). */
@RestController
@RequestMapping("/api/tenant-credit")
class TenantCreditController(private val service: TenantCreditService) {

    @GetMapping("/search")
    fun search(@RequestParam q: String): ApiResponse<List<DartCorp>> = ApiResponse.ok(service.search(q))

    @RequiresCredit
    @PostMapping("/analyze")
    fun analyze(@RequestBody req: TenantCreditRequest): ApiResponse<TenantCreditResponse> = ApiResponse.ok(service.analyze(req))
}
