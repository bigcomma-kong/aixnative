package com.aixnative.residential.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.residential.domain.LocationReport
import com.aixnative.residential.domain.MonthlyPrice
import com.aixnative.residential.domain.PresaleNotice
import com.aixnative.residential.service.LocationReportService
import com.aixnative.residential.service.ResidentialProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 주거·입지 무료 API(Phase 1) - 무료 입지 리포트(유입 top-of-funnel).
 * 경로 api/public 하위는 SecurityConfig 에서 공개(비인증) - 로그인 없이 체험 → 가입 유도.
 * 심화·딜분석은 별도 인증·크레딧 경로에서 제공(Phase 2+).
 */
@RestController
@RequestMapping("/api/public/residential")
class ResidentialController(
    private val locationReportService: LocationReportService,
    private val props: ResidentialProperties,
) {
    /**
     * 지도 표시용 설정. 카카오맵 JavaScript 키는 브라우저에서 SDK 를 로드해야 해 **공개될 수밖에 없는 키**이고,
     * 보호는 카카오 콘솔의 플랫폼 도메인 등록으로 한다(결제 clientKey 와 같은 성격).
     * 프런트에 하드코딩하지 않고 서버를 단일 소스로 두는 이유는 키 교체 시 재빌드가 필요 없게 하기 위함이다.
     * 키 미설정이면 `enabled=false` → 화면이 지도 영역을 아예 그리지 않는다(graceful).
     */
    @GetMapping("/map-config")
    fun mapConfig(): ApiResponse<MapConfigResponse> =
        ApiResponse.ok(MapConfigResponse(enabled = props.mapEnabled, jsKey = props.kakaoJsKey))

    /** 주소/지역 → 무료 입지 리포트. 예: /api/public/residential/location-report?query=서울 강남구 역삼동 */
    @GetMapping("/location-report")
    fun locationReport(@RequestParam query: String): ApiResponse<LocationReport> =
        ApiResponse.ok(locationReportService.report(query.trim()))

    /** 시군구(5) 기준 아파트 매매 트렌드(평단가·건수). 리포트 응답의 geo.sigunguCode 로 지연 로딩. */
    @GetMapping("/price-trend")
    fun priceTrend(
        @RequestParam sigungu: String,
        @RequestParam(defaultValue = "12") months: Int,
    ): ApiResponse<List<MonthlyPrice>> =
        ApiResponse.ok(locationReportService.priceTrend(sigungu.trim(), months))

    /** 분양 동향 - 최근 청약 분양공고(지역 필터 선택). 예: /presale?region=서울&limit=8 */
    @GetMapping("/presale")
    fun presale(
        @RequestParam(required = false) region: String?,
        @RequestParam(defaultValue = "8") limit: Int,
    ): ApiResponse<List<PresaleNotice>> =
        ApiResponse.ok(locationReportService.presaleNotices(region, limit))
}

/**
 * 지도 설정. [jsKey] 는 브라우저에 그대로 나가는 공개 키다(카카오 콘솔 도메인 등록으로 보호).
 * 서버 시크릿인 REST 키는 여기에 절대 담지 않는다.
 */
data class MapConfigResponse(
    val enabled: Boolean,
    val jsKey: String,
)
