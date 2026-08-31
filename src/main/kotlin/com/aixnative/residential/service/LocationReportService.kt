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
import com.aixnative.residential.domain.PresaleNotice
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

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
    private val naver: NaverLocalClient,
    private val juso: JusoClient,
    private val kapt: KaptClient,
    private val rtms: RtmsClient,
    private val ecos: EcosClient,
    private val cheongyak: CheongyakClient,
    private val executor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 주소/지역 문자열 → 입지 리포트. 지오코딩 실패 시에도 notes 로 사유 반환(예외 대신 부분 결과). */
    fun report(query: String): LocationReport {
        val notes = ArrayList<String>()

        val geo = geocode(query)
        if (geo == null) {
            notes += "주소를 인식하지 못했습니다. 동/도로명 또는 아파트명으로 다시 입력해 주세요."
            return LocationReport(query, null, emptyList(), emptyList(), emptyList(), notes)
        }

        // 4개 섹션(POI·단지·실거래·거시)을 동시 실행 - 순차 ~16콜(수십 초)을 섹션 병렬로 단축.
        val area = geo.areaLabel ?: query
        val fPoi = CompletableFuture.supplyAsync({ buildPoi(area) }, executor)
        val fComplex = CompletableFuture.supplyAsync({ kapt.complexesInDong(geo.bCode, COMPLEX_LIMIT) }, executor)
        val fDeals = CompletableFuture.supplyAsync(
            { rtms.aptTransactions(geo.sigunguCode, DEAL_YEARS).map { it.toDomain() } }, executor,
        )
        val fMacro = CompletableFuture.supplyAsync({ macro() }, executor)

        val nearby = fPoi.join()
        val complexes = fComplex.join()
        val deals = fDeals.join()
        val macro = fMacro.join()

        if (nearby.isEmpty() && !naver.isConfigured()) notes += "주변 시설은 준비 중입니다(네이버 검색 API 연동 대기)."
        if (complexes.isEmpty()) notes += "단지 스펙(K-apt)이 없습니다(data.go.kr 활용신청 확인)."
        if (deals.isEmpty()) notes += "최근 아파트 실거래가 없습니다(RTMS 아파트 API 활용신청 확인)."

        log.info(
            "[residential] 입지리포트 query='{}' geo={} nearby={}그룹 단지={} 실거래={}",
            query, geo.sigunguCode, nearby.size, complexes.size, deals.size,
        )
        return LocationReport(query, geo, nearby, complexes, deals, notes, macro)
    }

    @Volatile
    private var macroCache: Pair<Long, MacroContext?>? = null

    /** 거시(ECOS 기준금리·국고채) - 월 단위로만 바뀌므로 [MACRO_TTL_MS] 캐시(전 리포트 공유, 매 호출 비용 제거). */
    private fun macro(): MacroContext? {
        val now = System.currentTimeMillis()
        macroCache?.let { if (now - it.first < MACRO_TTL_MS) return it.second }
        val m = runCatching { ecos.latestRates() }.getOrNull()
            ?.let { MacroContext(it.baseRate, it.gov10y, it.asOf) }
        macroCache = now to m
        return m
    }

    /** 카테고리별 POI(네이버 지역검색) 동시 조회. 미설정 시 빈 리스트. */
    private fun buildPoi(area: String): List<NearbyGroup> {
        if (!naver.isConfigured()) return emptyList()
        return executor.parMap(POI_QUERIES) { (label, term) ->
            val places = naver.search(area, term, PER_GROUP)
            if (places.isEmpty()) null else NearbyGroup(label, places)
        }.filterNotNull()
    }

    /** 시군구코드(5) 기준 최근 [months]개월 아파트 매매 트렌드(평단가·건수) - 월별 동시 호출. */
    fun priceTrend(sigunguCode: String, months: Int): List<MonthlyPrice> {
        val m = months.coerceIn(1, 24)
        val yms = (0 until m).map { LocalDate.now().minusMonths(it.toLong()).format(YM) }
        return executor.parMap(yms) { rtms.aptMonthlyStat(sigunguCode, it) }
            .filterNotNull()
            .sortedBy { it.ym }
            .map { MonthlyPrice(it.ym, it.dealCount, it.avgPricePerPyeong) }
    }

    /** 분양 동향 - 최근 청약 분양공고(지역 필터 선택). Phase 3 - 별도 지연 로딩용. */
    fun presaleNotices(region: String?, limit: Int): List<PresaleNotice> =
        cheongyak.recentNotices(region?.trim()?.ifBlank { null }, limit.coerceIn(1, 20))

    /**
     * 지오코딩 - ⓪ 카카오 주소검색(좌표+법정동코드 동시) → ① juso → ② 네이버 장소해석(아파트명·랜드마크)
     * 후 다시 카카오·juso. 어디서도 코드를 못 얻으면 주소·POI 만 채우고 단지·실거래는 생략(부분 결과).
     *
     * 카카오를 앞에 둔 이유는 juso(business.juso.go.kr)가 Cloud Run 송신 IP 에서 connect timeout 이
     * 나기 때문이다. 폴백으로는 남겨 두되 정상 경로에서는 타지 않게 한다.
     */
    private fun geocode(query: String): GeoPoint? {
        // ⓪ 카카오 주소검색 - 좌표와 법정동코드를 **한 번에** 준다.
        //
        // juso 를 1순위로 두던 구조를 바꾼 이유: juso(business.juso.go.kr)가 Cloud Run 송신 IP 에서
        // connect timeout 으로 죽는다. 그러면 요청마다 5초씩 두 번(직접·네이버 후 재시도) 헛돌아
        // 응답이 10초대로 늘고, 법정동코드를 못 얻어 단지·실거래가 통째로 비어 버린다.
        // 카카오는 같은 정보를 한 번에 주므로 이쪽을 먼저 시도하고, juso 는 폴백으로 남긴다.
        kakao.geocode(query)?.takeIf { it.bCode.isNotBlank() }?.let {
            val addr = it.roadAddress ?: it.jibunAddress
            return it.copy(
                areaLabel = it.areaLabel ?: areaLabelOf(addr) ?: query,
                region = shortSido(addr),
            )
        }
        // ① 정식 주소면 juso 가 바로 법정동코드 반환.
        juso.resolveParcel(query)?.let {
            return withCoords(
                GeoPoint(
                    null, null, it.admCd, it.roadAddr.ifBlank { null }, null,
                    areaLabel = query, region = shortSido(it.roadAddr),
                ),
                it.roadAddr.ifBlank { null } ?: query,
            )
        }
        // ② 아파트명·랜드마크 → 네이버로 주소를 얻은 뒤, 그 주소로 다시 코드·좌표를 푼다.
        val place = naver.resolvePlace(query) ?: return null
        val addr = place.jibunAddress ?: place.roadAddress
        val area = areaLabelOf(place.jibunAddress ?: place.roadAddress) ?: query
        val region = shortSido(place.jibunAddress ?: place.roadAddress)

        // ②-a 카카오 먼저(코드+좌표 동시). 여기서도 juso 보다 앞에 두어 timeout 대기를 피한다.
        if (addr != null) {
            kakao.geocode(addr)?.takeIf { it.bCode.isNotBlank() }?.let {
                return it.copy(
                    roadAddress = place.roadAddress ?: it.roadAddress,
                    jibunAddress = place.jibunAddress ?: it.jibunAddress,
                    areaLabel = area,
                    region = region,
                )
            }
        }
        // ②-b 카카오가 못 풀면 juso 로 코드만이라도.
        juso.resolveParcel(addr)?.let {
            return withCoords(
                GeoPoint(null, null, it.admCd, place.roadAddress, place.jibunAddress, areaLabel = area, region = region),
                addr ?: query,
            )
        }
        // 둘 다 실패: 코드 없이(bCode 빈값 → 단지·실거래 생략) 주소·POI 만.
        return withCoords(
            GeoPoint(null, null, "", place.roadAddress, place.jibunAddress, areaLabel = area, region = region),
            addr ?: query,
        )
    }

    /**
     * 좌표 보강 - juso/네이버는 법정동코드와 주소는 주지만 **경위도를 주지 않는다**.
     * 지도를 그리려면 좌표가 필요하므로 카카오 주소검색으로 한 번 더 물어 채운다.
     *
     * 역할을 나눈 것이 요점이다: 법정동코드(단지·실거래 조회의 기준)는 juso 가 계속 권위를 갖고,
     * 카카오는 **좌표만** 채운다. 카카오가 실패하면 좌표만 비고 리포트 본문은 그대로 나온다
     * (화면은 좌표가 없으면 지도를 생략한다).
     */
    private fun withCoords(geo: GeoPoint, addressForLookup: String): GeoPoint {
        if (geo.hasCoords) return geo
        val hit = kakao.geocode(addressForLookup) ?: return geo
        if (hit.longitude == null || hit.latitude == null) return geo
        return geo.copy(longitude = hit.longitude, latitude = hit.latitude)
    }

    private fun RtmsClient.AptTrade.toDomain() = AptDeal(
        dealYmd = dealYmd, aptName = aptName, amountManwon = amountManwon,
        areaSqm = areaSqm, floor = floor, buildYear = buildYear, dong = umdNm.ifBlank { null },
    )

    private companion object {
        const val PER_GROUP = 5
        const val COMPLEX_LIMIT = 3
        const val DEAL_YEARS = 1
        const val MACRO_TTL_MS = 6 * 60 * 60 * 1000L // 거시 캐시 6시간
        val YM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        // (라벨, 네이버 지역검색 키워드) - 입지 핵심 카테고리.
        val POI_QUERIES = listOf(
            "지하철" to "지하철역",
            "학교" to "학교",
            "학원" to "학원",
            "마트" to "마트",
            "편의점" to "편의점",
            "병원" to "병원",
            "은행" to "은행",
        )
    }
}
