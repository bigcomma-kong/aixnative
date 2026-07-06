package com.aixnative.property.service

/**
 * 자산관리(PM) AI 프롬프트. 임대차 계약서 추출·AM 제출 보고서.
 * 공통 sections 출력 계약([SECTIONS_SCHEMA]/[SECTIONS_RULES])은 언더라이팅과 동일 렌더러를 재사용한다.
 */
object PropertyPrompts {

    /** 언더라이팅과 동일한 sections 출력 계약 재사용(generic 렌더러가 화면·보고서 처리). */
    val SECTIONS_SCHEMA = com.aixnative.underwriting.service.UnderwritingPrompts.SECTIONS_SCHEMA
    val SECTIONS_RULES = com.aixnative.underwriting.service.UnderwritingPrompts.SECTIONS_RULES

    /** 계약서 텍스트에서 임대차 필드를 구조화 추출. 모르는 값은 null(추정·창작 금지). 후속 저장 프리필용. */
    fun leaseExtract(text: String): String =
        "당신은 상업용 부동산 임대차 계약서를 구조화하는 추출기입니다.\n" +
            "아래 <TEXT>(임대차 계약서·약정서 등)에서 임대 관리에 필요한 필드를 뽑아 JSON 한 객체로만 출력하세요.\n" +
            "규칙: 계약서에 명시/명확히 추론되는 값만 채우고, 불확실하면 반드시 null. 새 숫자·당사자를 지어내지 마세요.\n" +
            "단위: 금액은 만원(예: '월 3,300만원'→3300, '보증금 12억'→120000), 면적은 평(㎡면 ÷3.305785), 비율은 %.\n" +
            "날짜는 yyyy-MM-dd 로 정규화(예: '2024년 3월 1일'→'2024-03-01'). 기간만 있으면 시작/종료일로 환산.\n" +
            "rentFreeMonths 는 렌트프리(무상임대) 개월수. escalationPct 는 임대료 인상률(%), nextEscalationDate 는 다음 인상 예정일.\n" +
            "confidence 는 추출 확신도 HIGH|MEDIUM|LOW.\n\n" +
            "[출력 스키마 - 단일 JSON 객체 하나만, 코드펜스(```)·주석 금지, 값 모르면 null]\n" +
            "{\n" +
            "  \"tenantName\": \"임차인명 또는 null\",\n" +
            "  \"unitLabel\": \"층/호(예: '10F') 또는 null\",\n" +
            "  \"areaPyeong\": 숫자(평) 또는 null,\n" +
            "  \"monthlyRentManwon\": 숫자(만원) 또는 null,\n" +
            "  \"depositManwon\": 숫자(만원) 또는 null,\n" +
            "  \"mgmtFeeManwon\": 숫자(만원) 또는 null,\n" +
            "  \"leaseStartDate\": \"yyyy-MM-dd 또는 null\",\n" +
            "  \"leaseEndDate\": \"yyyy-MM-dd 또는 null\",\n" +
            "  \"rentFreeMonths\": 숫자(개월) 또는 null,\n" +
            "  \"escalationPct\": 숫자(%) 또는 null,\n" +
            "  \"nextEscalationDate\": \"yyyy-MM-dd 또는 null\",\n" +
            "  \"notes\": \"특약·비고 한 줄 요약 또는 null\",\n" +
            "  \"confidence\": \"HIGH|MEDIUM|LOW\"\n" +
            "}\n\n" +
            "<TEXT>\n" + text + "\n</TEXT>"

    /**
     * AM 제출용 임대 관리 보고서 - 렌트롤·일정·리스크(코드 확정 수치는 <DATA>)를 근거로 sections 로 작성.
     * 수치는 <DATA> 값만 인용(창작 금지). verdict 는 자산 임대 상태 진단: 안정 | 주의 | 위험.
     */
    fun amReport(dataText: String, buildingName: String?): String =
        "당신은 부동산 자산관리(Property Management)에서 AM(자산운용사)에 제출하는 임대 관리 보고서를 작성하는 전문가입니다.\n" +
            "<DATA> 의 건물·렌트롤 집계·다가오는 일정·리스크(코드 확정 수치)를 근거로 AM 보고용 임대 관리 리뷰를 sections 로 작성하세요.\n" +
            "권장 섹션: ① 요약(임대 현황 한눈에 - 임차 건수·총 월임대료·임대율/공실·WALT) ② 렌트롤(임차인별 면적·임대료·보증금·만기, 표) ③ 다가오는 일정(만기·인상·렌트프리 종료 D-day, 표) ④ 임차인 집중도·만기 분산 평가 ⑤ 리스크·모니터링 액션(임박 만기 대응·재계약 협상·연체 점검) ⑥ AM 권고(우선 조치 3~5개).\n" +
            "verdict 는 안정 | 주의 | 위험 중 하나(임박 만기·특정 임차인 편중·데이터 공백이 크면 주의/위험). 모든 수치는 <DATA> 값만 인용하고 새 숫자를 만들지 마세요.\n\n" +
            SECTIONS_RULES + "\n" + SECTIONS_SCHEMA + "\n" +
            "[건물명] " + (buildingName ?: "(이름없음)") + "\n\n" +
            "<DATA>\n" + dataText + "\n</DATA>"
}
