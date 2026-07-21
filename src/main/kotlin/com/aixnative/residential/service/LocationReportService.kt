package com.aixnative.residential.service

import com.aixnative.integration.marketdata.service.EcosClient
import com.aixnative.integration.marketdata.service.JusoClient
import com.aixnative.integration.marketdata.service.RtmsClient
import com.aixnative.residential.domain.AptDeal
import com.aixnative.residential.domain.GeoPoint
import com.aixnative.residential.domain.LocationReport
import com.aixnative.residential.domain.MacroContext
import com.aixnative.residential.domain.MonthlyPrice
import com.aixnative.residential.domain.NearbyGroup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 무료 입지 리포트 오케스트레이터(Phase 1) - 주소 1건을 종합:
 *   주소 → [KakaoLocalClient] 지오코딩 → 주변 POI(카테고리별) + [KaptClient] 단지 스펙 + [RtmsClient] 아파트 실거래.
 *
 * 결정론 조립(Claude 미사용 - 무료·top-of-funnel). 각 소스는 부분 실패 허용(채워진 섹션만),
 * 미설정/미승인 소스는 notes 로 투명하게 안내. 크레딧 미차감.
 */
@Service
class LocationReportService(
    private val kakao: KakaoLocalClient,
    private val juso: JusoClient,
    private val kapt: KaptClient,
    private val rtms: RtmsClient,
    private val ecos: EcosClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 주소/지역 문자열 → 입지 리포트. 지오코딩 실패 시에도 notes 로 사유 반환(예외 대신 부분 결과). */
    fun report(query: String): LocationReport {
        val notes = ArrayList<String>()

        // 1순위 카카오(좌표+법정동코드 → POI 가능). 실패 시 juso 폴백(법정동코드만 - 비즈앱 불필요, 단지·실거래만).
        val geo = kakao.geocode(query) ?: jusoFallback(query, notes)
        if (geo == null) {
            notes += "주소를 인식하지 못했습니다. 도로명/동 단위로 더 구체적으로 입력해 주세요."
            return LocationReport(query, null, emptyList(), emptyList(), emptyList(), notes)
        }

        // POI 는 좌표가 있어야(카카오 지오코딩) 검색 가능. juso 폴백이면 생략.
        val nearby = if (geo.hasCoords) {
            POI_GROUPS.mapNotNull { (label, code) ->
                val places = kakao.nearby(geo.longitude!!, geo.latitude!!, code, RADIUS_M, PER_GROUP)
                if (places.isEmpty()) null else NearbyGroup(label, places)
            }
        } else {
            emptyList()
        }

        val complexes = kapt.complexesInDong(geo.bCode, COMPLEX_LIMIT)
        if (complexes.isEmpty()) notes += "단지 스펙(K-apt)이 없습니다(data.go.kr 활용신청 확인)."

        val deals = rtms.aptTransactions(geo.sigunguCode, DEAL_YEARS).map { it.toDomain() }
        if (deals.isEmpty()) notes += "최근 아파트 실거래가 없습니다(RTMS 아파트 API 활용신청 확인)."

        val macro = runCatching { ecos.latestRates() }.getOrNull()
            ?.let { MacroContext(baseRate = it.baseRate, gov10y = it.gov10y, asOf = it.asOf) }

        log.info(
            "[residential] 입지리포트 query='{}' geo={} nearby={}그룹 단지={} 실거래={}",
            query, geo.sigunguCode, nearby.size, complexes.size, deals.size,
        )
        return LocationReport(query, geo, nearby, complexes, deals, notes, macro)
    }

    /** 시군구코드(5) 기준 최근 [months]개월 아파트 매매 트렌드(평단가·건수). Phase 2 - 별도 지연 로딩용. */
    fun priceTrend(sigunguCode: String, months: Int): List<MonthlyPrice> =
        rtms.aptMonthlyTrend(sigunguCode, months.coerceIn(1, 24))
            .map { MonthlyPrice(it.ym, it.dealCount, it.avgPricePerPyeong) }

    /** 카카오 지오코딩 불가 시 juso(무료·비즈인증 불필요)로 법정동코드만 확보 → 단지·실거래는 동작, POI 는 생략. */
    private fun jusoFallback(query: String, notes: MutableList<String>): GeoPoint? {
        val parcel = juso.resolveParcel(query) ?: return null
        notes += "주변 시설(지하철·학교 등)은 카카오맵 서비스 활성화 후 표시됩니다. 단지·실거래는 표시됩니다."
        return GeoPoint(
            longitude = null,
            latitude = null,
            bCode = parcel.admCd,
            roadAddress = parcel.roadAddr.ifBlank { null },
            jibunAddress = null,
        )
    }

    private fun RtmsClient.AptTrade.toDomain() = AptDeal(
        dealYmd = dealYmd, aptName = aptName, amountManwon = amountManwon,
        areaSqm = areaSqm, floor = floor, buildYear = buildYear, dong = umdNm.ifBlank { null },
    )

    private companion object {
        const val RADIUS_M = 1000
        const val PER_GROUP = 5
        const val COMPLEX_LIMIT = 3
        const val DEAL_YEARS = 1
        // (라벨, 카카오 category_group_code) - 입지 핵심 카테고리.
        val POI_GROUPS = listOf(
            "지하철" to "SW8",
            "학교" to "SC4",
            "학원" to "AC5",
            "마트" to "MT1",
            "편의점" to "CS2",
            "병원" to "HP8",
            "은행" to "BK9",
        )
    }
}
