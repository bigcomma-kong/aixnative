package com.aixnative.residential.domain

/**
 * 지오코딩 결과 - 법정동코드(10) + 좌표(선택). RTMS 시군구코드(5)는 bCode 앞 5자리.
 * 좌표는 카카오 지오코딩일 때만 채워지고, juso 폴백(비즈앱 불필요)은 코드만 준다 → POI 생략.
 */
data class GeoPoint(
    val longitude: Double?,
    val latitude: Double?,
    val bCode: String,          // 법정동코드 10자리(카카오 b_code 또는 juso admCd)
    val roadAddress: String?,
    val jibunAddress: String?,
) {
    /** RTMS/단지 조회용 시군구코드(5). bCode 앞 5자리. */
    val sigunguCode: String get() = bCode.take(5)

    /** 좌표 보유 여부 - POI(주변시설) 검색 가능 조건. */
    val hasCoords: Boolean get() = longitude != null && latitude != null
}

/** 주변 시설 1건(카카오 로컬 POI). */
data class NearbyPlace(
    val name: String,
    val category: String,       // 지하철역·학교·마트 등(카카오 category_group_name)
    val distanceM: Int?,        // 중심점 기준 거리(m)
    val roadAddress: String?,
)

/** 카테고리별 주변 시설 묶음(입지 리포트 섹션 단위). */
data class NearbyGroup(
    val label: String,          // "지하철"·"학교"·"마트/편의점" 등
    val places: List<NearbyPlace>,
)

/** 아파트 단지 스펙(K-apt 기본+상세). 없으면 null 필드. */
data class ComplexInfo(
    val kaptCode: String,
    val name: String,
    val householdCount: Int?,   // 세대수
    val dongCount: Int?,        // 동수
    val approvalDate: String?,  // 사용승인일(yyyyMMdd 또는 yyyy-MM-dd)
    val parkingTotal: Int?,     // 총 주차대수(상세: 지상+지하)
    val heatingType: String?,   // 난방방식
    val subwayWalk: String? = null, // 지하철 도보시간("5분이내" 등, 상세)
    val busWalk: String? = null,    // 버스정류장 도보시간(상세)
)

/** 최근 아파트 실거래 1건(RTMS 아파트 매매). */
data class AptDeal(
    val dealYmd: String,        // yyyy.MM
    val aptName: String,
    val amountManwon: String,   // 거래금액(만원, 원문)
    val areaSqm: String,        // 전용면적㎡
    val floor: String,
    val buildYear: String,
    val dong: String?,          // 법정동명(umdNm)
)

/** 월별 아파트 매매 트렌드 1포인트(시군구 기준). */
data class MonthlyPrice(
    val ym: String,                 // yyyy.MM
    val dealCount: Int,             // 거래 건수
    val avgPricePerPyeong: Int,     // 평단가(만원/평)
)

/** 거시 맥락(ECOS) - "지금 금리 수준" 참고용. 값 없으면 null. */
data class MacroContext(
    val baseRate: Double?,      // 한국은행 기준금리(%)
    val gov10y: Double?,        // 국고채 10년(%)
    val asOf: String?,          // 기준 시점
)

/** 무료 입지 리포트 - 주소 1건에 대한 종합(부분 실패 허용, 채워진 섹션만). */
data class LocationReport(
    val query: String,
    val geo: GeoPoint?,
    val nearby: List<NearbyGroup>,
    val complexes: List<ComplexInfo>,
    val recentDeals: List<AptDeal>,
    val notes: List<String>,    // 미설정/미승인 소스 안내 등(투명성)
    val macro: MacroContext? = null,
)
