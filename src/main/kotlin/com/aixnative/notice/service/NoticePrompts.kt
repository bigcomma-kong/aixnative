package com.aixnative.notice.service

import com.aixnative.underwriting.service.UnderwritingPrompts

/**
 * 공매·매각·입찰 공고 프롬프트. 검증된 자산이라 문구를 그대로 옮겼다.
 *
 * 추출은 **숫자만 옮기게** 한다 - 평단가·수익률 같은 파생값은 [com.aixnative.notice.domain.NoticeCalculator]
 * 가 코드로 계산해 덮어쓴다. AI 가 곱셈을 시도할 이유를 아예 주지 않는 것이 요점이다.
 */
object NoticePrompts {

    /** 공고문 → 정형 JSON + 응찰 리스크. */
    fun extract(documentText: String, docName: String?): String =
        "당신은 한국 상업용 부동산 공고(공매·매각·입찰) 검토 전문가입니다.\n" +
            "아래 <DOCUMENT> 의 공고문 원문에서 핵심 정보를 JSON 으로 추출하고 응찰 리스크를 점검하세요.\n\n" +

            UnderwritingPrompts.COMMON_STRICT_RULES + "\n" +

            "[엄격 규칙]\n" +
            "- 추출 불가 항목은 null (추측·추정 금지, 문서에 없는 값 생성 금지)\n" +
            "- 금액은 숫자만 (단위: 원). 부대조건은 special_terms 에 서술\n" +
            "- 날짜·일시는 YYYY-MM-DD (시간 있으면 뒤에 HH:MM)\n" +
            "- **평단가·수익률 같은 파생 계산은 하지 마세요.** 코드가 따로 계산합니다. 문서에 적힌 값만 옮기세요.\n" +
            "- 코드펜스(```) 금지, JSON 외 어떤 텍스트도 출력 금지. 단일 JSON 객체 하나로 시작·끝\n\n" +

            "[추출 스키마]\n" + EXTRACT_SHAPE + "\n\n" +

            "[items/rounds 지침]\n" +
            "- 물건이 여러 건(개별매각)이면 items 에 물건별로 한 건씩. 단일 물건이면 items 는 대표 1건. " +
            "물건이 불명확하면 items 는 빈 배열 []\n" +
            "- round_prices 는 각 물건의 회차별 최저입찰가를 1차부터 순서대로 숫자(원)만. " +
            "저감표가 있으면 반드시 채우고, 없으면 []\n" +
            "- rounds 는 공고 전체의 회차 일정(회차 라벨 + 입찰일시). 없으면 []\n" +
            "- target/price 는 대표(1물건 또는 합계·1차 기준)로 채운다\n" +
            "[리스크 점검 지침]\n" +
            "- 입찰마감·개찰·잔금납부 일정이 임박·촉박하면 MEDIUM 이상\n" +
            "- 최저입찰가/감정가 대비 보증금 비율 이상, 명도·권리관계·특약 부담이 크면 severity 부여\n" +
            "- 자격·용도 제한이 응찰 가능성을 좌우하면 명시. 해당 리스크 없으면 risks 는 빈 배열 []\n\n" +

            "[문서명]\n" + (docName ?: "(이름없음)") + "\n\n" +
            "<DOCUMENT>\n" + documentText + "\n</DOCUMENT>"

    /** 추출된 공고 2~4건을 나란히 비교해 매력도·우선순위를 마크다운으로. */
    fun compare(noticesJson: String): String =
        "당신은 한국 상업용 부동산 매입/응찰 자문 애널리스트입니다.\n" +
            "아래 <NOTICES> 의 공고 2~4건을 비교해, 투자 관점의 상대적 매력도와 우선순위를 마크다운으로 정리하세요.\n\n" +

            UnderwritingPrompts.COMMON_STRICT_RULES +
            "- <NOTICES> 에 주어진 수치만 사용하고 없는 값은 만들지 마세요. 비교 불가한 항목은 '자료없음'으로 표기.\n" +
            "- 평단가 등 파생값이 이미 주어져 있으면 그대로 인용하고 다시 계산하지 마세요.\n\n" +

            "[출력 구성(마크다운)]\n" +
            "1) ## 비교 요약 - 각 공고의 한 줄 포지셔닝\n" +
            "2) ## 핵심 비교표 - 대상·용도·감정가/최저가·평단가·보증금·입찰마감·주요 리스크를 표로\n" +
            "3) ## 우선순위 - 응찰 추천 순서와 그 근거\n" +
            "4) ## 주의사항 - 공통·개별 리스크와 체결 전 확인할 것\n\n" +

            "JSON 이 아니라 마크다운으로 출력하세요. 코드펜스로 전체를 감싸지 마세요.\n\n" +

            "<NOTICES>\n" + noticesJson + "\n</NOTICES>"

    private const val EXTRACT_SHAPE = """{
  "notice_type": "공매|매각|입찰|공고|기타",
  "issuer": null,
  "target": { "name": null, "address": null, "use": null, "area_m2": null },
  "price": { "appraisal_krw": null, "min_bid_krw": null, "deposit_krw": null, "deposit_pct": null },
  "items": [ { "no": null, "unit": "호/동 등 표시", "address": null, "use": null, "area_m2": null, "appraisal_krw": null, "round_prices": [] } ],
  "rounds": [ { "round": "회차(예: 1차)", "datetime": "YYYY-MM-DD HH:MM 또는 null" } ],
  "schedule": { "bid_start": null, "bid_end": null, "open_date": null, "contract_by": null, "payment_by": null },
  "method": null,
  "qualification": null,
  "special_terms": [],
  "risks": [ { "severity": "HIGH|MEDIUM|LOW", "field": "관련 항목명", "message": "응찰 리스크 설명(한국어)" } ],
  "summary": "공고 핵심을 한국어 3~5줄로 요약(대상·가격·일정·응찰조건)"
}"""
}
