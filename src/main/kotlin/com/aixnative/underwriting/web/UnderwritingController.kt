package com.aixnative.underwriting.web

import com.aixnative.billing.service.RequiresCredit
import com.aixnative.common.web.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import com.aixnative.underwriting.domain.AnalysisType
import com.aixnative.underwriting.domain.DealExtractRequest
import com.aixnative.underwriting.domain.DealExtractResponse
import com.aixnative.underwriting.domain.DocAnalysisType
import com.aixnative.underwriting.domain.DocAnalyzeRequest
import com.aixnative.underwriting.domain.DocAnalyzeResponse
import com.aixnative.underwriting.service.ReportService
import com.aixnative.underwriting.service.UnderwritingService

/**
 * hero 엔드포인트. 모두 인증 필요(JWT). 모든 조회/저장은 현재 테넌트로 스코프됨.
 */
@RestController
@RequestMapping("/api/underwriting")
class UnderwritingController(
    private val service: UnderwritingService,
    private val reportService: ReportService,
) {

    /** 무료 — ProForma 지표만 계산(AI·크레딧 미사용). 입력값 미리보기용. */
    @PostMapping("/proforma")
    fun proForma(@Valid @RequestBody req: UnderwriteRequest): ApiResponse<ProFormaResponse> =
        ApiResponse.ok(service.proForma(req))

    /** 과금 — ProForma + AI 언더라이팅 내러티브. 성공 시 1 크레딧 차감, 잔액 0 이면 402. */
    @RequiresCredit
    @PostMapping("/analyze")
    fun analyze(@Valid @RequestBody req: UnderwriteRequest): ApiResponse<UnderwriteResponse> =
        ApiResponse.ok(service.analyze(req))

    /**
     * 과금 — 분석 단계 지정(SCREENING / MARKET_STUDY / UNDERWRITING / IC_MEMO).
     * 각 단계 = AI 1회 호출 = 1 크레딧. 성공 시에만 차감.
     */
    @RequiresCredit
    @PostMapping("/analyze/{type}")
    fun analyzeTyped(
        @PathVariable type: AnalysisType,
        @Valid @RequestBody req: UnderwriteRequest,
    ): ApiResponse<UnderwriteResponse> = ApiResponse.ok(service.analyze(type, req))

    /**
     * 무료 — 중복 분석 사전 확인. 동일 입력으로 최근(기본 60분) 같은 단계를 이미 실행했는지 알려준다.
     * AI·크레딧 미사용. 프런트가 실행 직전 호출해 재실행 전 사용자 확인을 받는 용도.
     */
    @PostMapping("/analyze/{type}/check-duplicate")
    fun checkDuplicate(
        @PathVariable type: AnalysisType,
        @Valid @RequestBody req: UnderwriteRequest,
    ): ApiResponse<DuplicateCheckResponse> = ApiResponse.ok(service.checkDuplicate(type, req))

    /**
     * 과금 — 문서/텍스트 기반 분석 단계(매입 추가분 + 신규 트랙).
     * UNDERWRITING_GUIDE / BUILDING_RESEARCH / TAX_PRICE_DIAGNOSIS / BOV / AM_QUARTERLY /
     * HOLD_SELL_REFI / DEV_FEASIBILITY / MARKET_RESEARCH_DEEP. 각 호출 = 1 크레딧(성공 시).
     */
    @RequiresCredit
    @PostMapping("/analyze-doc/{type}")
    fun analyzeDoc(
        @PathVariable type: DocAnalysisType,
        @Valid @RequestBody req: DocAnalyzeRequest,
    ): ApiResponse<DocAnalyzeResponse> = ApiResponse.ok(service.analyzeDoc(type, req))

    /**
     * 무료 — 딜/기사 텍스트를 구조화 추출(분석 폼 프리필용 진입점). AI 1회 호출이지만 크레딧 미차감.
     * 인증 + AI 설정만 게이트. 실제 분석(가격예측·언더라이팅)이 과금 단위.
     */
    @PostMapping("/extract-deal")
    fun extractDeal(@Valid @RequestBody req: DealExtractRequest): ApiResponse<DealExtractResponse> =
        ApiResponse.ok(service.extractDeal(req))

    /**
     * 투자 보고서(HTML). 주어진 run 이 속한 딜의 분석 단계들을 합본해 인쇄 친화 문서로 반환.
     * 무료(저장된 분석 결과 조립일 뿐 AI 미호출). 다른 테넌트의 run 이면 404.
     */
    @GetMapping("/report/{runId}", produces = [MediaType.TEXT_HTML_VALUE])
    fun report(@PathVariable runId: Long): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(reportService.buildHtml(runId))

    /** 읽기전용 공유 링크 발급(멱등). 토큰 반환 → 프런트가 {origin}/api/public/report/{token} 링크 구성. */
    @PostMapping("/report/{runId}/share")
    fun shareReport(@PathVariable runId: Long): ApiResponse<ShareResponse> =
        ApiResponse.ok(ShareResponse(service.enableReportShare(runId)))

    /**
     * 무료 — 한 딜의 완료된 단계(스크리닝·시장조사·언더라이팅·투심) 모음. 합본 탭 화면용.
     * 저장된 결과 조립일 뿐 AI 미호출. 테넌트 스코프(다른 사용자 딜은 보이지 않음).
     */
    @GetMapping("/deal-stages")
    fun dealStages(@RequestParam dealName: String): ApiResponse<DealStagesResponse> =
        ApiResponse.ok(service.dealStages(dealName))

    /** 내 분석 이력 목록 (최신순). */
    @GetMapping("/runs")
    fun runs(): ApiResponse<List<RunSummary>> = ApiResponse.ok(service.listRuns())

    /** 내 딜 대시보드 — 딜명으로 집계한 요약(최근 활동순). 리텐션 허브. */
    @GetMapping("/deals")
    fun deals(): ApiResponse<List<DealSummary>> = ApiResponse.ok(service.myDeals())

    /** 분석 이력 상세 — 저장된 입력/결과. 다른 테넌트의 id 면 404. */
    @GetMapping("/runs/{id}")
    fun run(@PathVariable id: Long): ApiResponse<RunDetail> = ApiResponse.ok(service.getRun(id))
}
