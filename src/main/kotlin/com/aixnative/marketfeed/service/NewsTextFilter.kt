package com.aixnative.marketfeed.service
import com.aixnative.marketfeed.domain.NewsItem

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
     * '오늘의 딜' 카드가 될 실제 거래(딜) 기사인지. 소스 구분 없이 균일 적용:
     * 한국어 게이트 → 비부동산/매크로 노이즈 차단 → **부동산 컨텍스트 앵커 AND 거래 시그널 동시 충족**.
     * 시황·정책·오피니언(거래가 아님)과 매크로 칼럼은 제외한다. 일본판/외신은 한국어 게이트로 먼저 차단.
     */
    fun isRelevant(item: NewsItem): Boolean {
        if (!isKorean(item.title)) return false
        val text = "${item.title} ${item.summary}"
        if (NON_RE_NOISE.any { text.contains(it) }) return false
        val hasContext = RE_CONTEXT.any { text.contains(it) }
        val hasDeal = DEAL_SIGNALS.any { text.contains(it) }
        return hasContext && hasDeal
    }

    /** 한국어 기사인가 — 한글(가-힣)이 있고 일본어 가나(히라가나·가타카나)가 없어야 통과. */
    fun isKorean(title: String): Boolean =
        HANGUL.containsMatchIn(title) && !KANA.containsMatchIn(title)

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

    private val HANGUL = Regex("[가-힣]")
    private val KANA = Regex("[\\u3040-\\u30ff]") // 히라가나 + 가타카나

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

    // 전체 제외(비부동산·매크로 노이즈): 영화·스포츠·문화 + 매크로/오피니언 누수 보강.
    // 컨텍스트+거래 시그널을 우연히 갖춰도(예: "배우 ○○ 강남 빌딩 매입") 딜이 아니므로 하드 차단.
    private val NON_RE_NOISE = setOf(
        "박스오피스", "영화", "흥행", "관객", "개봉", "예매율", "스크린",
        "월드컵", "축구", "야구", "농구", "배구", "올림픽", "프로야구", "무승부", "대표팀", "피파",
        "공연", "연극", "뮤지컬", "콘서트", "전시회", "갤러리", "드라마", "예능", "아이돌",
        "걸그룹", "보이그룹", "앨범", "시청률", "예고편", "넷플릭스",
        // 연예·인물 가십·라이프스타일(휴먼 인터레스트) — 부동산 키워드가 섞여도 투자 딜 아님.
        "배우", "가수", "연예", "열애", "이혼", "재혼", "출산", "육아", "임신", "젖몸살",
        "인생극장", "순간포착", "리뷰 기사", "유튜버", "인플루언서",
        // 매크로/오피니언 칼럼 누수(딜 아님).
        "골목상권", "소상공인", "자영업", "대선", "총선",
    )

    // 부동산 컨텍스트 앵커(자산·주체·현황). 거래 시그널과 '동시에' 있어야 딜로 인정.
    private val RE_CONTEXT = setOf(
        "부동산", "빌딩", "사옥", "오피스", "프라임", "물류센터", "물류창고", "데이터센터", "IDC",
        "호텔", "리조트", "리테일", "상가", "쇼핑몰", "백화점", "근린상가", "토지", "부지",
        "연면적", "공실", "임대료", "평당", "매물", "공시지가", "재건축", "재개발",
        "리츠", "자산운용", "상업용",
    )

    // 거래(딜) 시그널 — 실제 매매·인수 등 트랜잭션을 뜻하는 표현. 최소 1개 필수.
    private val DEAL_SIGNALS = setOf(
        "매각", "매입", "매매", "인수", "거래", "원매자", "매수자", "우선협상", "우선협상대상자",
        "낙찰", "유찰", "입찰", "공매", "자산편입", "셀다운", "리파이낸싱", "리파이낸스",
        "인수합병", "지분인수", "사들이", "팔린", "매각가", "인수가", "분양", "딜",
    )
}
