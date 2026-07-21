package com.aixnative.residential.service

import com.aixnative.integration.marketdata.service.MarketDataProperties
import com.aixnative.residential.domain.PresaleNotice
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate

/**
 * 청약홈 APT 분양정보(한국부동산원, data.go.kr odcloud ApplyhomeInfoDetailSvc). 최근 모집공고를
 * 지역(선택)으로 걸러 최신순으로. 키 = [MarketDataProperties.dataGoKrKey](해당 서비스 활용신청 필요).
 * 미설정/미승인(401) 시 graceful 빈 리스트. odcloud 는 serviceKey 쿼리파라미터 인증.
 */
@Component
class CheongyakClient(
    private val marketProps: MarketDataProperties,
    private val props: ResidentialProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 최근 [days]일 이내 분양공고(지역 필터 선택) 최신순 [limit]건. 키 미설정/미승인 시 빈 리스트. */
    fun recentNotices(region: String?, limit: Int = 8, days: Long = 120): List<PresaleNotice> {
        if (!props.cheongyakEnabled || marketProps.dataGoKrKey.isBlank()) return emptyList()
        val since = LocalDate.now().minusDays(days).toString() // yyyy-MM-dd
        return runCatching {
            val uri = UriComponentsBuilder.fromHttpUrl(APT_NOTICE)
                .queryParam("serviceKey", marketProps.dataGoKrKey)
                .queryParam("page", "1")
                .queryParam("perPage", "200")
                .queryParam("cond[RCRIT_PBLANC_DE::GTE]", since) // 최근 공고만
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return emptyList()
            val data = mapper.readTree(body).path("data")
            if (!data.isArray) return emptyList()
            data.asSequence()
                .map { toNotice(it) }
                .filter { region.isNullOrBlank() || (it.region?.contains(region) == true) }
                .sortedByDescending { it.noticeDate ?: "" }
                .take(limit)
                .toList()
        }.getOrElse { log.warn("[cheongyak] 분양정보 수집 실패: {}", it.message); emptyList() }
    }

    private fun toNotice(n: JsonNode): PresaleNotice = PresaleNotice(
        houseName = n.path("HOUSE_NM").asText("-"),
        kind = n.path("HOUSE_SECD_NM").asText("").ifBlank { null },
        region = n.path("SUBSCRPT_AREA_CODE_NM").asText("").ifBlank { null },
        address = n.path("HSSPLY_ADRES").asText("").ifBlank { null },
        totalSupply = n.path("TOT_SUPLY_HSHLDCO").asText("").trim().toIntOrNull(),
        noticeDate = n.path("RCRIT_PBLANC_DE").asText("").ifBlank { null },
        receiptStart = n.path("RCEPT_BGNDE").asText("").ifBlank { null },
        receiptEnd = n.path("RCEPT_ENDDE").asText("").ifBlank { null },
        winnerDate = n.path("PRZWNER_PRESNATN_DE").asText("").ifBlank { null },
        homepage = n.path("HMPG_ADRES").asText("").ifBlank { null },
        detailUrl = n.path("PBLANC_URL").asText("").ifBlank { null },
    )

    private companion object {
        const val APT_NOTICE = "https://api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1/getAPTLttotPblancDetail"
    }
}
