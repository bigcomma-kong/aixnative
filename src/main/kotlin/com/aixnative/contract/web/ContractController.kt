package com.aixnative.contract.web

import com.aixnative.billing.service.RequiresCredit
import com.aixnative.common.web.ApiResponse
import com.aixnative.contract.domain.ContractReviewRequest
import com.aixnative.contract.domain.ContractReviseRequest
import com.aixnative.contract.domain.ContractSetCompareRequest
import com.aixnative.contract.service.ContractReviewService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AI 계약서 검토. 원문은 JSON 으로 받는다 - 파일 업로드는 `POST /api/documents/extract` 가 따로 담당하고,
 * 클라이언트가 그 결과 텍스트를 여기에 실어 보낸다(추출과 분석의 분리).
 *
 * [revise]·[compareSet] 이 원문 대신 **runId** 를 받는 것이 설계의 핵심이다.
 * 조회가 `AiToolRunService` 를 거치므로 테넌트 격리가 기존 코드 재사용만으로 보장되고,
 * 같은 원문을 두 번 올릴 필요도 없다.
 */
@RestController
@RequestMapping("/api/contract")
class ContractController(
    private val service: ContractReviewService,
) {
    /** 계약서 1건 검토(조항별 리스크·공란·정합성). */
    @RequiresCredit
    @PostMapping("/review")
    fun review(@Valid @RequestBody req: ContractReviewRequest): ApiResponse<ContractResponse> =
        ApiResponse.ok(service.review(req))

    /** 검토 결과 → 조항별 수정안(레드라인). */
    @RequiresCredit
    @PostMapping("/revise")
    fun revise(@RequestBody req: ContractReviseRequest): ApiResponse<ContractResponse> =
        ApiResponse.ok(service.revise(req.runId, req.perspective))

    /** 같은 딜에 묶인 검토 결과 2~4건의 문서 사이 관계 심사. */
    @RequiresCredit
    @PostMapping("/compare-set")
    fun compareSet(@RequestBody req: ContractSetCompareRequest): ApiResponse<ContractSetCompareResponse> =
        ApiResponse.ok(service.compareSet(req))
}
