package com.aixnative.residential.service

import com.aixnative.integration.marketdata.service.MarketDataProperties
import com.aixnative.residential.domain.ComplexInfo
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * 공동주택(K-apt) 단지 정보 - 법정동(10) 내 단지 목록 + 단지별 기본 스펙(세대수·동수·사용승인·주차).
 * data.go.kr 공동주택관리정보시스템 open API(키 = [MarketDataProperties.dataGoKrKey], API 활용신청 필요).
 * 키/승인 없거나 무결과 시 graceful(빈 리스트/null 필드).
 */
@Component
class KaptClient(
    private val marketProps: MarketDataProperties,
    private val props: ResidentialProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class ComplexRef(val kaptCode: String, val name: String)

    /** 법정동코드(10) 내 단지 상위 [limit]개의 기본 스펙. 키 미설정/미승인 시 빈 리스트. */
    fun complexesInDong(bCode: String, limit: Int = 3): List<ComplexInfo> {
        if (!enabled() || bCode.length < 10) return emptyList()
        val refs = listComplexes(bCode).take(limit)
        return refs.mapNotNull { basicInfo(it) }
    }

    private fun enabled(): Boolean = props.kaptEnabled && marketProps.dataGoKrKey.isNotBlank()

    /** 법정동 단지 목록(kaptCode + 이름). */
    private fun listComplexes(bCode: String): List<ComplexRef> = runCatching {
        val uri = UriComponentsBuilder.fromHttpUrl(LEGALDONG_LIST)
            .queryParam("serviceKey", marketProps.dataGoKrKey)
            .queryParam("bjdCode", bCode)
            .queryParam("numOfRows", 20)
            .queryParam("pageNo", 1)
            .queryParam("_type", "json")
            .build(false).encode().toUri()
        val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return emptyList()
        // AptListService3 JSON: response.body.items 가 배열 직접. (XML→JSON 변환형은 items.item → 둘 다 지원.)
        val items = mapper.readTree(body).path("response").path("body").path("items")
        itemsToList(items).mapNotNull { node ->
            val code = node.path("kaptCode").asText("").ifBlank { return@mapNotNull null }
            ComplexRef(code, node.path("kaptName").asText("-"))
        }
    }.getOrElse { log.debug("[kapt] 목록 실패: {}", it.message); emptyList() }

    /** 단지 기본 스펙(세대수·동수·사용승인일·주차·난방). */
    private fun basicInfo(ref: ComplexRef): ComplexInfo? = runCatching {
        val uri = UriComponentsBuilder.fromHttpUrl(BASIS_INFO)
            .queryParam("serviceKey", marketProps.dataGoKrKey)
            .queryParam("kaptCode", ref.kaptCode)
            .queryParam("_type", "json")
            .build(false).encode().toUri()
        val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return null
        val item = mapper.readTree(body).path("response").path("body").path("item")
        if (item.isMissingNode || item.isNull) return null
        // 필드는 문자열("1")·숫자(0.0) 혼재 → asInt 로 통일 파싱. 주차(kaptdPcnt)는 상세서비스라 bass 엔 보통 없음(null).
        val ground = item.path("kaptdPcnt").asInt(0)
        val under = item.path("kaptdPcntu").asInt(0)
        ComplexInfo(
            kaptCode = ref.kaptCode,
            name = item.path("kaptName").asText(ref.name),
            householdCount = item.path("kaptdaCnt").asInt(0).takeIf { it > 0 },
            dongCount = item.path("kaptDongCnt").asInt(0).takeIf { it > 0 },
            approvalDate = item.path("kaptUsedate").asText("").ifBlank { null },
            parkingTotal = (ground + under).takeIf { it > 0 },
            heatingType = item.path("codeHeatNm").asText("").ifBlank { null },
        )
    }.getOrElse { log.debug("[kapt] 기본정보({}) 실패: {}", ref.kaptCode, it.message); null }

    /** items 노드 → 원소 리스트. 배열 직접([...]) 또는 XML변환형({item:[...]}·{item:{...}}) 모두 처리. */
    private fun itemsToList(items: com.fasterxml.jackson.databind.JsonNode): List<com.fasterxml.jackson.databind.JsonNode> {
        if (items.isArray) return items.toList()
        val inner = items.path("item")
        return when {
            inner.isArray -> inner.toList()
            inner.isObject -> listOf(inner)
            else -> emptyList()
        }
    }

    private companion object {
        // 공동주택관리정보시스템(K-apt) - 라이브 프로빙으로 확정한 실제 경로(호스트 1613000, V3).
        // 목록=단지 목록제공 서비스(15057332, bjdCode), 기본정보=기본 정보제공 서비스(15058453, kaptCode).
        // ⚠ 이 서비스들은 DATA_GO_KR 키(계정)에 별도 활용신청(구독)돼 있어야 함(미구독 시 403).
        const val LEGALDONG_LIST = "https://apis.data.go.kr/1613000/AptListService3/getLegaldongAptList3"
        const val BASIS_INFO = "https://apis.data.go.kr/1613000/AptBasisInfoServiceV4/getAphusBassInfoV4"
    }
}
