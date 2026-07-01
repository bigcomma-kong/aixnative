package com.aixnative.integration.marketdata.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import com.aixnative.integration.marketdata.domain.LawdCode

/** 상업 실거래 통계 — 중위 평당가(만원/평)와 표본 건수. 가격 예측 거래사례법 입력. */
data class CompStats(val medianPyeongManwon: Long, val count: Int)

/**
 * 실측 시장지표 오케스트레이터 — ECOS(매크로)·R-ONE(임대시장)·RTMS(실거래) 결과를
 * AI 프롬프트에 주입할 facts 블록으로 조립한다. 각 소스는 독립적으로 graceful degrade
 * (키 미설정·실패 시 해당 줄만 빠지고 분석은 계속). 값이 있으면 AI 가 추정 대신 인용 → 신뢰도 ↑.
 */
@Service
class MarketDataService(
    private val ecosClient: EcosClient,
    private val rebRoneClient: RebRoneClient,
    private val rtmsClient: RtmsClient,
    private val jusoClient: JusoClient,
    private val vWorldClient: VWorldClient,
    private val props: MarketDataProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 위치·자산유형 기반 실측 시장 facts 통합 블록. 사용 가능한 소스가 하나도 없으면 빈 문자열.
     * 시장조사·심화리서치 프롬프트의 <ASSET>/<DATA> 말미에 덧붙여 실측 앵커로 쓴다.
     */
    fun marketFacts(location: String?, assetType: String?): String {
        val parts = listOf(
            ratesFactLine(),
            rebRoneClient.rentYieldFactLine(location, assetType),
            comparablesFactLine(location),
        ).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "" else parts.joinToString("")
    }

    /** ECOS 매크로 한 줄(기준금리·국고채). 없으면 빈 문자열. */
    fun ratesFactLine(): String {
        val r = ecosClient.latestRates() ?: return ""
        return buildString {
            append("\n[실측 매크로 — 출처 한국은행 ECOS, 기준 ").append(r.asOf).append("] ")
            r.baseRate?.let { append("기준금리 ").append(it).append("% · ") }
            r.gov3y?.let { append("국고채3년 ").append(it).append("% · ") }
            r.gov10y?.let { append("국고채10년 ").append(it).append("%") }
        }.trimEnd(' ', '·')
    }

    /** RTMS 상업업무용 실거래 comps 한 줄 — 위치→시군구코드(내장 표)→최근 거래(만원→억 환산). */
    fun comparablesFactLine(address: String?): String {
        if (address.isNullOrBlank()) return ""
        return try {
            val lawdCd = LawdCode.resolve(address) ?: return ""
            val trades = rtmsClient.commercialTransactions(lawdCd, 2)
            if (trades.isEmpty()) return ""
            buildString {
                append("\n[실거래가 — 국토부 RTMS 상업업무용, 최근 거래] ")
                trades.take(5).forEach { t ->
                    append(t.dealYmd).append(" ").append(toEok(t.amountManwon))
                        .append("(전용 ").append(t.areaSqm).append("㎡, ").append(t.floor).append("층); ")
                }
                append("위 거래는 국토부 실측이므로 comps/거래사례 작성 시 추정 대신 인용하세요.")
            }
        } catch (e: Exception) {
            log.warn("[MarketData] comparablesFactLine 실패: {}", e.message)
            ""
        }
    }

    /**
     * RTMS 토지 실거래 comps 한 줄 — 개발 토지비·세무 매입가 검증용(상업 comps 와 별도).
     * 위치→시군구코드→최근 토지 거래(만원→억). 미해석/거래없음 시 빈 문자열.
     */
    fun landComparablesFactLine(address: String?): String {
        if (address.isNullOrBlank()) return ""
        return try {
            val lawdCd = LawdCode.resolve(address) ?: return ""
            val trades = rtmsClient.landTransactions(lawdCd, 2)
            if (trades.isEmpty()) return ""
            buildString {
                append("\n[실거래가 — 국토부 RTMS 토지, 최근 거래] ")
                trades.take(5).forEach { t ->
                    append(t.dealYmd).append(" ").append(toEok(t.amountManwon))
                        .append("(").append(t.landType).append(" ").append(t.areaSqm).append("㎡, ")
                        .append(t.landUse).append("); ")
                }
                append("위 토지 거래는 국토부 실측이므로 토지비·평당가 작성 시 추정 대신 인용하세요.")
            }
        } catch (e: Exception) {
            log.warn("[MarketData] landComparablesFactLine 실패: {}", e.message)
            ""
        }
    }

    /**
     * 용도지역·개별공시지가 실측 한 줄 — 개발 용적률·세무 가격적정성 검증용.
     * 용도지역: 필지주소→V-World 지오코더→req/data(표준 인증키로 동작). 공시지가: juso→PNU→V-World ned
     * (NED 등록 시에만). 둘 다 미해석/미설정이면 빈 문자열(graceful).
     */
    fun landValuationFactLine(parcelAddress: String?): String {
        if (parcelAddress.isNullOrBlank()) return ""
        return try {
            val use = vWorldClient.geocodeParcel(parcelAddress)?.let { vWorldClient.landUseByPoint(it) }
            val price = if (props.landPriceEnabled)
                jusoClient.resolveParcel(parcelAddress)?.let { vWorldClient.landPrice(it.pnu()) } else null
            if (use == null && price == null) return ""
            buildString {
                append("\n[실측 용도지역·공시지가 — 출처 V-World(국토부)] ")
                append(parcelAddress).append(": ")
                use?.let { append("용도지역 ").append(it) }
                price?.let {
                    if (use != null) append(" · ")
                    append("개별공시지가 ").append(it.pricePerSqm).append("원/㎡(").append(it.year).append("년)")
                    if (it.landArea.isNotBlank()) append(", 면적 ").append(it.landArea).append("㎡")
                }
                append(" — 위는 공식 실측이므로 용적률·토지가 적정성 검토 시 추정 대신 인용하세요.")
            }
        } catch (e: Exception) {
            log.warn("[MarketData] landValuationFactLine 실패: {}", e.message)
            ""
        }
    }

    /** 상업 실거래 중위 평당가(만원/평) + 건수 — 가격 예측(거래사례법)용 구조화 통계. 없으면 null. */
    fun compStats(location: String?): CompStats? {
        if (location.isNullOrBlank()) return null
        return try {
            val lawdCd = LawdCode.resolve(location) ?: return null
            val trades = rtmsClient.commercialTransactions(lawdCd, 2)
            val pyeongPrices = trades.mapNotNull { t ->
                val amt = numeric(t.amountManwon) ?: return@mapNotNull null
                val sqm = numeric(t.areaSqm) ?: return@mapNotNull null
                if (sqm <= 0) null else amt / (sqm / com.aixnative.underwriting.domain.PriceEstimator.PYEONG_SQM)
            }.sorted()
            if (pyeongPrices.isEmpty()) return null
            val median = pyeongPrices[pyeongPrices.size / 2]
            CompStats(medianPyeongManwon = Math.round(median), count = pyeongPrices.size)
        } catch (e: Exception) {
            log.warn("[MarketData] compStats 실패: {}", e.message)
            null
        }
    }

    private fun numeric(s: String): Double? = s.replace("[^0-9.]".toRegex(), "").toDoubleOrNull()

    /** RTMS dealAmount(만원, 콤마 포함) → "N.N억". 파싱 실패 시 원문+만원. */
    private fun toEok(manwon: String): String {
        val m = manwon.replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: return "$manwon 만원"
        return "${Math.round(m / 1000.0) / 10.0}억"
    }
}
