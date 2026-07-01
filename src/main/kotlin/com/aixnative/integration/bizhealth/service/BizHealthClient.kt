package com.aixnative.integration.bizhealth.service

import com.aixnative.integration.marketdata.service.MarketDataProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import com.aixnative.integration.bizhealth.domain.BizHealthResult
import com.aixnative.integration.bizhealth.domain.BizStatus
import com.aixnative.integration.bizhealth.domain.CorpInfo
import com.aixnative.integration.bizhealth.domain.PensionInfo
import com.aixnative.integration.bizhealth.domain.Sanction
import com.aixnative.integration.bizhealth.domain.SanctionResult

/**
 * 거래상대방 실사 — 공공데이터 4종(전부 data.go.kr 키 재사용). 각 호출은 독립 graceful
 * (개별 실패/미승인 시 available=false). 사업자번호는 상태·제재, 상호는 기업정보·연금에 사용.
 * (MASTERN BizHealthService 이식 — 키는 신규 발급, 복사 금지.)
 */
@Component
class BizHealthClient(
    private val props: MarketDataProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 사업자번호(+선택 상호)로 4종 실사. 둘 다 없으면 전부 unavailable. */
    fun check(bizNo: String?, name: String?): BizHealthResult {
        val cleanNo = bizNo?.replace("[^0-9]".toRegex(), "")?.takeIf { it.length == 10 }
        return BizHealthResult(
            bizNo = cleanNo,
            name = name?.takeIf { it.isNotBlank() },
            status = cleanNo?.let { businessStatus(it) } ?: BizStatus(false),
            sanctions = cleanNo?.let { sanctions(it) } ?: SanctionResult(false),
            corp = name?.takeIf { it.isNotBlank() }?.let { corpOutline(it) } ?: CorpInfo(false),
            pension = name?.takeIf { it.isNotBlank() }?.let { pension(it) } ?: PensionInfo(false),
        )
    }

    /** 국세청 사업자등록상태 — POST {"b_no":[...]}, serviceKey 는 쿼리. */
    private fun businessStatus(bizNo: String): BizStatus {
        if (props.dataGoKrKey.isBlank()) return BizStatus(false)
        return try {
            val uri = UriComponentsBuilder
                .fromHttpUrl("https://api.odcloud.kr/api/nts-businessman/v1/status")
                .queryParam("serviceKey", props.dataGoKrKey)
                .build(false).encode().toUri()
            val body = rest.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"b_no":["$bizNo"]}""")
                .retrieve().body(String::class.java) ?: return BizStatus(false)
            val d = mapper.readTree(body).path("data").firstOrNull() ?: return BizStatus(false)
            BizStatus(
                available = true,
                status = d.path("b_stt").asText("").ifBlank { d.path("tax_type").asText("") },
                taxType = d.path("tax_type").asText(""),
                closedDate = d.path("end_dt").asText(""),
            )
        } catch (e: Exception) {
            log.warn("[BizHealth] 사업자상태 실패: {}", e.message)
            BizStatus(false)
        }
    }

    /** 조달청 부정당제재 — bizno 기준 유효 제재 목록. */
    private fun sanctions(bizNo: String): SanctionResult {
        if (props.dataGoKrKey.isBlank()) return SanctionResult(false)
        return try {
            val uri = UriComponentsBuilder
                .fromHttpUrl("https://apis.data.go.kr/1230000/ao/UsrInfoService02/getUnptRsttCorpInfo02")
                .queryParam("serviceKey", props.dataGoKrKey)
                .queryParam("bizno", bizNo)
                .queryParam("inqryDiv", "1")
                .queryParam("type", "json")
                .queryParam("numOfRows", "20")
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return SanctionResult(false)
            val items = mapper.readTree(body).path("response").path("body").path("items")
            val list = (if (items.isArray) items else items.path("item")).mapNotNull { toSanction(it) }
            SanctionResult(available = true, count = list.size, items = list)
        } catch (e: Exception) {
            log.warn("[BizHealth] 부정당제재 실패: {}", e.message)
            SanctionResult(false)
        }
    }

    private fun toSanction(n: JsonNode): Sanction? {
        val from = first(n, "rstrtPrdBgnDt", "RSTRT_PRD_BGN_DT", "spotDt")
        if (from.isBlank() && n.path("ntceInsttNm").asText("").isBlank()) return null
        return Sanction(
            from = from,
            to = first(n, "rstrtPrdEndDt", "RSTRT_PRD_END_DT"),
            org = first(n, "ntceInsttNm", "NTCE_INSTT_NM", "insttNm"),
            basis = first(n, "lawDivNm", "LAW_DIV_NM", "relLawNm"),
        )
    }

    /** 금융위 기업기본정보 — 상호로 대표·설립·업종. */
    private fun corpOutline(name: String): CorpInfo {
        if (props.dataGoKrKey.isBlank()) return CorpInfo(false)
        return try {
            val uri = UriComponentsBuilder
                .fromHttpUrl("https://apis.data.go.kr/1160100/service/GetCorpBasicInfoService_V2/getCorpOutline_V2")
                .queryParam("serviceKey", props.dataGoKrKey)
                .queryParam("corpNm", name)
                .queryParam("resultType", "json")
                .queryParam("numOfRows", "20")
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return CorpInfo(false)
            val items = mapper.readTree(body).path("response").path("body").path("items").path("item")
            val n = (if (items.isArray) items.firstOrNull() else items) ?: return CorpInfo(false)
            CorpInfo(
                available = true,
                corpName = first(n, "corpNm", "CORP_NM"),
                repName = first(n, "enpRprFnm", "ENP_RPR_FNM", "rprsvNm"),
                estbDate = first(n, "enpEstbDt", "ENP_ESTB_DT"),
                industry = first(n, "enpMainBizNm", "ENP_MAIN_BIZ_NM", "sicNm"),
            )
        } catch (e: Exception) {
            log.warn("[BizHealth] 기업정보 실패: {}", e.message)
            CorpInfo(false)
        }
    }

    /** 국민연금 가입사업장 — 상호 검색 → seq 상세(규모·고지액). */
    private fun pension(name: String): PensionInfo {
        if (props.dataGoKrKey.isBlank()) return PensionInfo(false)
        return try {
            val searchUri = UriComponentsBuilder
                .fromHttpUrl("https://apis.data.go.kr/B552015/NpsBplcInfoInqireServiceV2/getBassInfoSearchV2")
                .queryParam("serviceKey", props.dataGoKrKey)
                .queryParam("wkplNm", name)
                .queryParam("numOfRows", "5")
                .queryParam("pageNo", "1")
                .queryParam("dataType", "JSON")
                .build(false).encode().toUri()
            val searchBody = rest.get().uri(searchUri).retrieve().body(String::class.java) ?: return PensionInfo(false)
            val item = mapper.readTree(searchBody).path("response").path("body").path("items").path("item")
                .let { if (it.isArray) it.firstOrNull() else it.takeIf { n -> !n.isMissingNode } }
                ?: return PensionInfo(false)
            val seq = item.path("seq").asText("")
            val workplace = item.path("wkplNm").asText("")
            if (seq.isBlank()) return PensionInfo(true, workplaceName = workplace)

            val detailUri = UriComponentsBuilder
                .fromHttpUrl("https://apis.data.go.kr/B552015/NpsBplcInfoInqireServiceV2/getDetailInfoSearchV2")
                .queryParam("serviceKey", props.dataGoKrKey)
                .queryParam("seq", seq)
                .queryParam("dataType", "JSON")
                .build(false).encode().toUri()
            val detailBody = rest.get().uri(detailUri).retrieve().body(String::class.java)
            val d = detailBody?.let { mapper.readTree(it).path("response").path("body").path("item") }
            PensionInfo(
                available = true,
                workplaceName = workplace,
                members = d?.path("jnngpCnt")?.asText(""),
                monthlyNotice = d?.path("crrmmNtcAmt")?.asText(""),
                industry = d?.path("vldtVlKrnNm")?.asText(""),
            )
        } catch (e: Exception) {
            log.warn("[BizHealth] 국민연금 실패: {}", e.message)
            PensionInfo(false)
        }
    }

    /** 여러 후보 키 중 첫 비어있지 않은 값(대소문자·별칭 폴백). */
    private fun first(n: JsonNode, vararg keys: String): String {
        for (k in keys) {
            val v = n.path(k).asText("")
            if (v.isNotBlank()) return v
        }
        return ""
    }
}
