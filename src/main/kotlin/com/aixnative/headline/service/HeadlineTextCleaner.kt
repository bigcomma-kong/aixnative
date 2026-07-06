package com.aixnative.headline.service

import com.aixnative.marketfeed.service.NewsTextFilter

/**
 * 구글뉴스 제목 정제 — 헤드라인 보드에 실을 만한 기사 제목만 남긴다(결정론).
 * 구글뉴스 RSS 제목은 '<기사 제목> - <매체>' 꼴이라 꼬리 매체명을 떼고, 매체 홈/섹션 항목
 * (예: '코어비트 - 코어비트', 'SPI - 상업용 부동산 콘텐츠 …', 'SPI PRO - …')은 걸러낸다.
 * 필터는 휴리스틱(튜닝 가능) — 애매하면 버리는 쪽(정확도 우선).
 */
object HeadlineTextCleaner {

    /** 수집 소스 라벨('HEADLINE:SPI')에서 매체명('SPI')만 추출. */
    fun outletOf(source: String): String = source.removePrefix(HEADLINE_PREFIX).trim()

    /**
     * @return 표시용으로 정제된 제목, 또는 실을 가치가 없으면 null(홈/섹션/외국어/너무 짧음).
     */
    fun clean(rawTitle: String, source: String): String? {
        val outlet = outletOf(source)
        var t = NewsTextFilter.stripHtml(rawTitle).trim()

        // 꼬리 ' - <매체>' 제거(구글뉴스 표준 포맷).
        val suffix = " - $outlet"
        if (t.endsWith(suffix)) t = t.dropLast(suffix.length).trim()

        if (t.isBlank()) return null
        if (t == outlet) return null                 // '코어비트 - 코어비트' → '코어비트'
        if (t.startsWith(outlet)) return null         // 'SPI - …', 'SPI PRO - …' (사이트명·섹션)
        if (t.length < MIN_TITLE_LEN) return null
        if (!NewsTextFilter.isKorean(t)) return null  // 영문/일본판 내비게이션 항목 차단
        return t
    }

    private const val HEADLINE_PREFIX = "HEADLINE:"
    private const val MIN_TITLE_LEN = 8
}
