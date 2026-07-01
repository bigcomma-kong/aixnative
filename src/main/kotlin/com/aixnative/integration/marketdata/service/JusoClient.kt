package com.aixnative.integration.marketdata.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * 행정안전부 도로명주소(juso.go.kr) 지오코더 — 주소 문자열 → 법정동코드(10)+번+지+산여부.
 * 무료·즉시 발급(비즈인증 불필요). 공시지가/용도지역 조회용 PNU(19) 조립의 전제.
 * 키 미설정/미해석 시 null(graceful degrade).
 */
@Component
class JusoClient(
    private val props: MarketDataProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** juso 검색 첫 결과의 필지 식별자(PNU 조립용). 미해석 시 null. */
    fun resolveParcel(address: String?): Parcel? {
        if (address.isNullOrBlank() || props.jusoKey.isBlank()) return null
        return try {
            val uri = UriComponentsBuilder
                .fromHttpUrl("https://business.juso.go.kr/addrlink/addrLinkApi.do")
                .queryParam("confmKey", props.jusoKey)
                .queryParam("keyword", address)
                .queryParam("resultType", "json")
                .queryParam("currentPage", "1")
                .queryParam("countPerPage", "1")
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return null
            val juso = mapper.readTree(body).path("results").path("juso").firstOrNull() ?: return null
            val admCd = juso.path("admCd").asText("")
            if (admCd.length != 10) return null
            Parcel(
                admCd = admCd,
                landGbn = if (juso.path("mtYn").asText("0") == "1") "2" else "1", // 1=산→PNU 2, else 1
                bun = juso.path("lnbrMnnm").asText("0"),
                ji = juso.path("lnbrSlno").asText("0"),
                roadAddr = juso.path("roadAddr").asText(""),
            )
        } catch (e: Exception) {
            log.warn("[juso] 주소 해석 실패: {}", e.message)
            null
        }
    }

    /** PNU(19) 조립용 필지 식별자. */
    data class Parcel(val admCd: String, val landGbn: String, val bun: String, val ji: String, val roadAddr: String) {
        /** 19자리 PNU = 법정동코드(10) + 지목(1) + 번(4) + 지(4). */
        fun pnu(): String = admCd + landGbn + pad(bun) + pad(ji)

        private fun pad(v: String): String =
            v.trim().toIntOrNull()?.let { "%04d".format(it) } ?: "0000"
    }
}
