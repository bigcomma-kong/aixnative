package com.aixnative.marketfeed.ingest

/**
 * 수집 기사 정규화·중복키·노이즈 필터·자산분류. 전부 결정론적(키·AI 불필요).
 * MASTERN NewsAggregator 의 필터 전략(링크 정규화, 비부동산 노이즈, 동음이의 게이트)을 이식.
 */
object NewsTextFilter {

    /** 중복제거 키 — 프로토콜·쿼리·프래그먼트 제거 후 lowercase. */
    fun normalizeLink(link: String): String =
        link.trim()
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .lowercase()

    /** HTML 태그·엔티티 제거 후 공백 정리(RSS description 정제). */
    fun stripHtml(raw: String): String =
        raw.replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 부동산/딜 관련 기사인지. 신뢰 소스(RSS)는 통과, 느슨한 소스(구글뉴스)는
     * 비부동산 노이즈 차단 + 부동산 앵커 1개 이상 필수(동음이의 게이트).
     */
    fun isRelevant(item: NewsItem): Boolean {
        val text = "${item.title} ${item.summary}"
        if (NON_RE_NOISE.any { text.contains(it) }) return false
        if (!item.loose) return true
        return RE_ANCHORS.any { text.contains(it) }
    }

    /** 자산유형 추정(앱 표준: 오피스|물류|호텔|리테일). 불명확하면 null. 섹터 힌트 우선. */
    fun classifyAssetType(item: NewsItem): String? {
        item.sectorHint?.let { hint ->
            SECTOR_TO_ASSET[hint]?.let { return it }
        }
        val text = "${item.title} ${item.summary}"
        return ASSET_KEYWORDS.firstOrNull { (_, kws) -> kws.any { text.contains(it) } }?.first
    }

    /** 위치 추정 — '서울 ○○구' / 광역시·도 첫 매칭. 없으면 null. */
    fun guessLocation(item: NewsItem): String? {
        val text = "${item.title} ${item.summary}"
        Regex("(서울|부산|대구|인천|광주|대전|울산|세종|경기|성남|용인|고양|수원)\\s?[가-힣]{0,3}(구|시|동)?")
            .find(text)?.let { return it.value.trim() }
        return null
    }

    private val SECTOR_TO_ASSET = mapOf(
        "office" to "오피스", "logistics" to "물류", "hotel" to "호텔",
        "retail" to "리테일", "datacenter" to "물류", "reit" to "오피스",
    )

    // 자산유형 → 키워드(순서 = 우선순위).
    private val ASSET_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "물류" to listOf("물류센터", "물류창고", "데이터센터", "IDC", "콜드체인"),
        "호텔" to listOf("호텔", "특급호텔", "리조트", "레지던스"),
        "리테일" to listOf("리테일", "상가", "쇼핑몰", "백화점", "근린상가"),
        "오피스" to listOf("오피스", "사옥", "빌딩", "업무용", "프라임"),
    )

    // 전체 제외(비부동산 노이즈): 영화·스포츠·문화.
    private val NON_RE_NOISE = setOf(
        "박스오피스", "영화", "흥행", "관객", "개봉", "예매율", "스크린",
        "월드컵", "축구", "야구", "농구", "배구", "올림픽", "프로야구", "무승부", "대표팀", "피파",
        "공연", "연극", "뮤지컬", "콘서트", "전시회", "갤러리", "드라마", "예능", "아이돌",
        "걸그룹", "보이그룹", "앨범", "시청률", "예고편", "넷플릭스",
    )

    // 부동산 앵커(느슨한 소스 통과 조건).
    private val RE_ANCHORS = setOf(
        "부동산", "빌딩", "사옥", "오피스", "임대", "임차", "매매", "매입", "매각", "분양", "공실",
        "평당", "자산운용", "리츠", "시행", "시공", "디벨로퍼", "낙찰", "감정가", "유찰", "입찰",
        "연면적", "임대료", "매물", "시세", "상업용", "공시지가", "재건축", "재개발", "물류센터",
        "상가", "토지", "준공", "착공", "프롭테크", "우선협상", "데이터센터", "호텔", "NPL", "PF",
    )
}
