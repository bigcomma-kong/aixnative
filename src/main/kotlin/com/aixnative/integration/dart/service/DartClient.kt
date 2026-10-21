package com.aixnative.integration.dart.service

import com.aixnative.integration.dart.domain.DartCompanyOverview
import com.aixnative.integration.dart.domain.DartCorp
import com.aixnative.integration.dart.domain.DartFinancials
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** OpenDART 인증키(신규 발급 - opendart.fss.or.kr). 미설정이면 기능 비활성(503 안내). */
@ConfigurationProperties(prefix = "dart")
data class DartProperties(
    val apiKey: String = "",
) {
    val enabled: Boolean get() = apiKey.isNotBlank()
}

/**
 * OpenDART - 회사 검색(고유번호 목록), 기업개황, 사업보고서 주요 재무.
 *
 * 고유번호 목록은 ZIP 안의 XML(수만 건)이라 StAX 로 흘려 읽고 메모리에 하루 캐시한다.
 * Cloud Run 은 인스턴스가 내려가면 캐시가 사라지므로 첫 검색이 몇 초 걸릴 수 있다.
 *
 * 비상장·소규모 법인은 사업보고서 재무가 없어 조회가 비는 경우가 많다(감사보고서 원문 파싱은 아직 미지원).
 */
@Component
class DartClient(
    private val props: DartProperties,
    private val mapper: ObjectMapper,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val rest: RestClient = builder.clone()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(20)) // 고유번호 ZIP 다운로드
            },
        )
        .build()

    @Volatile private var corps: List<DartCorp> = emptyList()
    @Volatile private var loadedAt: Instant = Instant.EPOCH

    val enabled: Boolean get() = props.enabled

    /** 회사명·종목코드 검색 - 상장사 우선, 이름이 짧을수록(정확할수록) 앞. */
    fun searchCompanies(keyword: String, limit: Int = 10): List<DartCorp> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return corpList()
            .filter { it.corpName.contains(kw) || it.stockCode?.contains(kw) == true }
            .sortedWith(compareBy<DartCorp>({ it.stockCode == null }, { it.corpName.length }, { it.corpName }))
            .take(limit)
    }

    /** 단일 회사 확정 - 고유번호(8자리)·종목코드(6자리)·정확한 회사명 순, 그다음 부분 일치 1순위. */
    fun resolve(identifier: String): DartCorp? {
        val key = identifier.trim()
        if (key.isEmpty()) return null
        val list = corpList()
        if (key.matches(Regex("\\d{8}"))) list.firstOrNull { it.corpCode == key }?.let { return it }
        if (key.matches(Regex("\\d{6}"))) list.firstOrNull { it.stockCode == key }?.let { return it }
        list.firstOrNull { it.corpName == key }?.let { return it }
        return searchCompanies(key, 1).firstOrNull()
    }

    fun overview(corpCode: String): DartCompanyOverview? {
        val root = getJson("company.json", mapOf("corp_code" to corpCode)) ?: return null
        fun t(k: String) = root.path(k).asText("").trim().takeIf { it.isNotEmpty() }
        return DartCompanyOverview(
            corpCode = corpCode,
            corpName = t("corp_name") ?: return null,
            stockCode = t("stock_code"),
            ceoName = t("ceo_nm"),
            corpClass = t("corp_cls"),
            bizrNo = t("bizr_no"),
            address = t("adres"),
            establishDate = t("est_dt"),
            fiscalMonth = t("acc_mt"),
        )
    }

    /**
     * 최근 [years]개 사업연도의 사업보고서(11011) 재무 - 연결(CFS) 우선, 없으면 별도(OFS). 최신 연도부터.
     * 아직 공시되지 않은 직전 연도는 빠진다(3월 말 이전 조회 등).
     */
    fun annualFinancials(corpCode: String, years: Int = 5): List<DartFinancials> {
        val base = LocalDate.now().year - 1
        return (base downTo base - years + 1).mapNotNull { year ->
            financials(corpCode, year, "CFS") ?: financials(corpCode, year, "OFS")
        }
    }

    private fun financials(corpCode: String, year: Int, fsDiv: String): DartFinancials? {
        val root = getJson(
            "fnlttSinglAcntAll.json",
            mapOf("corp_code" to corpCode, "bsns_year" to year.toString(), "reprt_code" to ANNUAL_REPORT, "fs_div" to fsDiv),
        ) ?: return null
        val list = root.path("list").takeIf { it.isArray && it.size() > 0 } ?: return null
        val current = LinkedHashMap<String, BigDecimal>()
        val prior = LinkedHashMap<String, BigDecimal>()
        list.forEach { item ->
            val name = item.path("account_nm").asText("").replace(" ", "")
            amount(item.path("thstrm_amount").asText(""))?.let { current.putIfAbsent(name, it) }
            amount(item.path("frmtrm_amount").asText(""))?.let { prior.putIfAbsent(name, it) }
        }
        val fs = DartFinancials(
            year = year,
            fsDiv = fsDiv,
            totalAssets = find(current, "자산총계", "총자산"),
            totalLiabilities = find(current, "부채총계", "총부채"),
            totalEquity = find(current, "자본총계", "총자본"),
            currentAssets = find(current, "유동자산"),
            currentLiabilities = find(current, "유동부채"),
            inventory = find(current, "재고자산"),
            // '매출' 단독 키는 넣지 않는다 - 포함 매칭에서 '매출원가'·'매출총이익'이 매출로 잡혀 이익률이 틀어진다.
            revenue = find(current, "매출액", "수익(매출액)", "영업수익"),
            operatingIncome = find(current, "영업이익", "영업이익(손실)"),
            netIncome = find(current, "당기순이익", "당기순이익(손실)"),
            operatingCashFlow = find(current, "영업활동현금흐름", "영업활동으로인한현금흐름"),
            priorRevenue = find(prior, "매출액", "수익(매출액)", "영업수익"),
            priorOperatingIncome = find(prior, "영업이익", "영업이익(손실)"),
        )
        return fs.takeIf { it.totalAssets != null }
    }

    /**
     * 계정명 매칭 - 정확히 같은 이름을 먼저, 없으면 포함 관계로.
     * '유동자산' 을 포함으로만 찾으면 '비유동자산' 이 먼저 걸리는 함정이 있어 정확 일치를 우선한다.
     */
    private fun find(amounts: Map<String, BigDecimal>, vararg keys: String): BigDecimal? {
        for (k in keys) amounts[k]?.let { return it }
        for (k in keys) {
            amounts.entries.firstOrNull { (name, _) -> name.contains(k) && !name.startsWith("비$k") }?.let { return it.value }
        }
        return null
    }

    private fun amount(raw: String): BigDecimal? {
        val t = raw.replace(",", "").trim()
        if (t.isEmpty() || t == "-") return null
        return t.toBigDecimalOrNull()
    }

    /** status 000 이 아니면 null(013 = 조회된 데이터 없음 - 흔한 정상 케이스). */
    private fun getJson(path: String, params: Map<String, String>): JsonNode? {
        check(props.enabled) { "OpenDART 인증키가 설정되지 않았습니다." }
        val uri = UriComponentsBuilder.fromUriString("$BASE/$path")
            .queryParam("crtfc_key", props.apiKey)
            .apply { params.forEach { (k, v) -> queryParam(k, v) } }
            .build().encode().toUri()
        val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return null
        val root = mapper.readTree(body)
        val status = root.path("status").asText()
        if (status != "000") {
            if (status != "013") log.warn("[DART] {} status={} message={}", path, status, root.path("message").asText())
            return null
        }
        return root
    }

    private fun corpList(): List<DartCorp> {
        if (corps.isNotEmpty() && Duration.between(loadedAt, Instant.now()) < CACHE_TTL) return corps
        synchronized(this) {
            if (corps.isNotEmpty() && Duration.between(loadedAt, Instant.now()) < CACHE_TTL) return corps
            check(props.enabled) { "OpenDART 인증키가 설정되지 않았습니다." }
            val zip = rest.get().uri("$BASE/corpCode.xml?crtfc_key={k}", props.apiKey)
                .retrieve().body(ByteArray::class.java)
                ?: error("DART 고유번호 목록을 받지 못했습니다.")
            corps = parseCorpCodes(zip)
            loadedAt = Instant.now()
            log.info("[DART] 고유번호 목록 로드 {}건", corps.size)
            return corps
        }
    }

    companion object {
        private const val BASE = "https://opendart.fss.or.kr/api"
        private const val ANNUAL_REPORT = "11011"
        private val CACHE_TTL: Duration = Duration.ofHours(24)

        /** ZIP(CORPCODE.xml) → 회사 목록. 응답이 ZIP 이 아니면(키 오류 시 JSON/XML 오류문) 빈 목록 대신 예외. */
        fun parseCorpCodes(zip: ByteArray): List<DartCorp> {
            val out = ArrayList<DartCorp>(120_000)
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                var entry = zis.nextEntry ?: error("DART 고유번호 응답이 ZIP 이 아닙니다(인증키를 확인하세요).")
                while (!entry.name.endsWith(".xml", ignoreCase = true)) {
                    entry = zis.nextEntry ?: return out
                }
                val factory = XMLInputFactory.newFactory().apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false) // XXE 차단
                    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                }
                val r = factory.createXMLStreamReader(zis, "UTF-8")
                var tag = ""
                var code = ""
                var name = ""
                var stock = ""
                while (r.hasNext()) {
                    when (r.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            tag = r.localName
                            if (tag == "list") { code = ""; name = ""; stock = "" }
                        }
                        XMLStreamConstants.CHARACTERS -> when (tag) {
                            "corp_code" -> code += r.text
                            "corp_name" -> name += r.text
                            "stock_code" -> stock += r.text
                        }
                        XMLStreamConstants.END_ELEMENT -> {
                            if (r.localName == "list" && code.isNotBlank() && name.isNotBlank()) {
                                out += DartCorp(code.trim(), name.trim(), stock.trim().takeIf { it.isNotEmpty() })
                            }
                            tag = ""
                        }
                    }
                }
            }
            return out
        }
    }
}
