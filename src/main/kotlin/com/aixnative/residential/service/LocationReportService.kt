package com.aixnative.residential.service

import com.aixnative.integration.marketdata.service.RtmsClient
import com.aixnative.residential.domain.AptDeal
import com.aixnative.residential.domain.LocationReport
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
    private val kapt: KaptClient,
    private val rtms: RtmsClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 주소/지역 문자열 → 입지 리포트. 지오코딩 실패 시에도 notes 로 사유 반환(예외 대신 부분 결과). */
    fun report(query: String): LocationReport {
        val notes = ArrayList<String>()
        val geo = kakao.geocode(query)
        if (geo == null) {
            notes += "주소를 좌표로 변환하지 못했습니다(카카오 키 미설정이거나 주소를 더 구체적으로)."
            return LocationReport(query, null, emptyList(), emptyList(), emptyList(), notes)
        }

        val nearby = POI_GROUPS.mapNotNull { (label, code) ->
            val places = kakao.nearby(geo.longitude, geo.latitude, code, RADIUS_M, PER_GROUP)
            if (places.isEmpty()) null else NearbyGroup(label, places)
        }

        val complexes = kapt.complexesInDong(geo.bCode, COMPLEX_LIMIT)
        if (complexes.isEmpty()) notes += "단지 스펙(K-apt)이 없습니다(data.go.kr 활용신청 확인)."

        val deals = rtms.aptTransactions(geo.sigunguCode, DEAL_YEARS).map { it.toDomain() }
        if (deals.isEmpty()) notes += "최근 아파트 실거래가 없습니다(RTMS 아파트 API 활용신청 확인)."

        log.info(
            "[residential] 입지리포트 query='{}' geo={} nearby={}그룹 단지={} 실거래={}",
            query, geo.sigunguCode, nearby.size, complexes.size, deals.size,
        )
        return LocationReport(query, geo, nearby, complexes, deals, notes)
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
