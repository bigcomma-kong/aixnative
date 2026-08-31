package com.aixnative.contract.service

import com.aixnative.contract.domain.ReviewPerspective
import com.aixnative.underwriting.service.UnderwritingPrompts

/**
 * 계약서 검토 프롬프트. 검증된 자산이라 문구를 그대로 옮겼고, 공통 규칙은
 * [UnderwritingPrompts.COMMON_STRICT_RULES] 를 재사용한다(구분자 내부를 데이터로 취급하는
 * 프롬프트 인젝션 방어 문구가 이미 들어 있다).
 *
 * 설계 핵심 세 가지:
 *  1. **계약 유형을 미리 가르지 않는다.** 유형별 분기를 두면 사용자가 유형을 잘못 고르는 순간
 *     엉뚱한 체크리스트로 검토된다. AI 가 문서에서 스스로 판별하게 하고, 유형 특화 조항까지 한 번에 본다.
 *  2. **조항 인용을 강제한다.** "위험합니다" 같은 추상적 지적은 실무에서 쓸 수 없다.
 *     모든 지적에 조항 번호와 원문 인용을 요구해야 사용자가 바로 협상 테이블에 올릴 수 있다.
 *  3. **공란·정합성을 따로 뽑는다.** 체결본에 남은 `[*]` 공란과 조문 번호 중복·상호참조 모순은
 *     리스크 검토와 성격이 달라 섞으면 묻힌다.
 */
object ContractPrompts {

    /** 검토 관점 문장 - 중립이면 균형 점검, 아니면 그 편을 보호하는 관점으로. */
    private fun perspectiveLine(p: ReviewPerspective): String =
        if (p == ReviewPerspective.NEUTRAL) {
            "- 검토 관점: 중립. 양 당사자 각각에게 불리한 조항을 균형 있게 점검하되, " +
                "어느 쪽에 불리한지 riskAssessment 의 issue 에 명시하세요.\n"
        } else {
            "- 검토 관점: **${p.label}** 입장. '${p.label}'에게 불리·위험하거나 상대방에게 일방적으로 유리한 조항을 " +
                "우선 찾아내고, '${p.label}'를 보호할 협상 포인트를 제시하세요.\n"
        }

    /** 통합 계약 검토 - 사실 추출 + 리스크·공란·정합성. */
    fun review(documentText: String, docName: String?, perspective: ReviewPerspective): String =
        "당신은 부동산·기업 계약을 검토하는 법무 실사 전문가입니다.\n" +
            "아래 계약서를 비판적으로 검토하세요. 단순 사실 나열이 아니라, 불리·독소·모호 조항, 미기재 공란, " +
            "조문 오류·모순, 누락된 보호장치를 찾아내고 협상 포인트까지 제시하는 것이 핵심입니다.\n\n" +

            UnderwritingPrompts.COMMON_STRICT_RULES + "\n" +

            perspectiveLine(perspective) +
            "- 계약 유형을 미리 가르지 말고, contractType 을 문서에서 스스로 판별한 뒤 그 유형에서 중요한 조항을 빠짐없이 검토하세요.\n" +
            "- 유형별 특화 조항까지 반드시 리스크 관점으로 점검: " +
            "임대차→보증금·월세·관리비·렌트프리·인상률(상한)·원상복구·갱신·중도해지 위약·제소전화해 / " +
            "매매→계약금·중도금·잔금·소유권이전·하자담보·제한물권 말소·해제 / " +
            "용역·도급→대금 지급조건·검수·지체상금·하자보수·지식재산권·재하도급 / " +
            "NDA·MOU→비밀유지 범위·기간·위반 책임·구속력.\n" +
            "- **조항 인용 필수**: 모든 지적(issue·evidence·blanks·inconsistencies)에 근거 조항 번호" +
            "(예: '제12조 제(4)항', '별지 1')를 반드시 표기하세요.\n" +
            "- **미기재 공란 탐지**: 체결본인데도 남아 있는 공란·placeholder(`[*]`, `[ ]`, 대괄호 안 미확정 값, " +
            "`[000]`, 날짜·금액·통지처 공란 등)를 빠짐없이 찾아 blanks 에 나열하세요. 체결 전 반드시 채워야 하는 항목입니다.\n" +
            "- **오류·정합성 점검**: 조항 번호 중복·누락, 오기, 조문 간 상호참조 모순(A조가 B조를 인용하는데 결과가 모순), " +
            "산식·수치 방향 오류, 정의만 하고 미사용인 용어를 inconsistencies 에 나열하세요.\n" +
            "- **신용보강 점검**: 상대방 채무불이행·도산에 대비한 연대보증·이행보증·담보·모회사 보증 등이 없는지 확인하고, " +
            "없으면 리스크로 지적하세요.\n" +
            "- 정보가 부족해도 '분석 불가'로 끝내지 말고, 확인된 범위에서 최대한 검토하고 불명확한 부분은 '확인 필요'로 표시하세요.\n\n" +

            "## 문서명: ${docName ?: "(이름없음)"}\n\n" +

            "## 1) 계약 사실 추출 (문서에 없으면 null / 빈 배열)\n" +
            "- contractTitle: 계약서 제목\n" +
            "- contractType: 매매/임대차/용역/도급/MOU/NDA 중 선택\n" +
            "- assetType: 대상 부동산 자산 유형. 오피스/리테일/물류/주거/호텔/데이터센터/토지/기타 중 선택. " +
            "부동산과 무관한 계약이면 null\n" +
            "- parties: 배열, 각 항목 role(갑/을)/name/businessNumber\n" +
            "- contractDate / effectiveDate / expiryDate: YYYY-MM-DD\n" +
            "- totalAmount: 단위 포함 (예: 5억원)\n" +
            "- paymentTerms: 지급 방식·시기\n" +
            "- autoRenewal: true/false/null\n" +
            "- terminationConditions: 주요 해지 사유 배열\n" +
            "- penalties: 위약금/손해배상 조건\n" +
            "- specialTerms: 중요 특약 배열\n" +
            "- disputeResolution: 관할법원/중재 등\n" +
            "- keyObligations: 각 당사자의 주요 의무 배열\n" +
            "- summary: 계약 핵심 3~5문장\n\n" +

            "## 2) 리스크·공란·정합성 검토 (가장 중요 - 성의껏, 조항 번호를 인용해 구체적으로)\n" +
            "- riskAssessment: 우리에게 불리/위험한 조항을 3~8개 골라 각각 다음 필드로:\n" +
            "    · clause: 대상 조항·주제 (예: \"일방적 중도해지권\", \"지체상금\", \"손해배상 상한 부재\")\n" +
            "    · severity: \"HIGH\"|\"MEDIUM\"|\"LOW\" - 우리 측 리스크 크기\n" +
            "    · issue: 무엇이 왜 불리/위험한지 구체적으로 (추상적 우려 금지)\n" +
            "    · evidence: 판단 근거가 된 계약서 원문 구절을 최대한 인용 (없으면 해당 조항 요지)\n" +
            "    · recommendation: 어떻게 수정·삭제·협상해야 하는지 실무 제안\n" +
            "- missingProtections: 우리 보호를 위해 있어야 하는데 누락된 조항 배열 " +
            "(예: \"손해배상액 상한\", \"비밀유지 조항\", \"불가항력\", \"연대보증·이행보증\")\n" +
            "- negotiationPoints: 우선순위 높은 협상·수정 항목 배열 (실행 가능한 문장)\n" +
            "- blanks: 미기재 공란·placeholder 배열. 각 항목 \"조항 위치 - 무엇이 비어있는지\" " +
            "(예: \"제17조 통지처 - 주소·이메일·전화·담당자 전부 [*]\"). 없으면 빈 배열.\n" +
            "- inconsistencies: 조문 오류·정합성 결함 배열. 각 항목 \"조항 위치 - 문제\" " +
            "(예: \"제6조 - 항번호 (1) 중복으로 이후 번호 밀림\"). 없으면 빈 배열.\n" +
            "- overallRisk: \"HIGH\"|\"MEDIUM\"|\"LOW\" - 계약 전체 위험도\n" +
            "- reviewOpinion: 검토 총평 2~3문장 (체결 가부 관점 포함). " +
            "단 최종 법률자문이 아니라 실무 검토 보조 의견임.\n\n" +

            "## 응답 규칙\n" +
            "- 날짜는 반드시 YYYY-MM-DD. 문서에 없는 값은 null 또는 빈 배열.\n" +
            "- 근거 없는 추정·창작 금지. evidence 는 최대한 원문에서 인용.\n" +
            "- JSON 객체 하나만 출력. 코드펜스·설명·주석 금지.\n\n" +

            "## 출력 형식\n" +
            REVIEW_SHAPE + "\n\n" +

            "## 문서 내용\n" +
            "<DOCUMENT>\n" + documentText + "\n</DOCUMENT>"

    /** 검토 결과(JSON) → 조항별 수정안(레드라인). 계약서 전문을 다시 쓰지 않는다. */
    fun revise(reviewJson: String, perspective: ReviewPerspective): String =
        "당신은 한국 상업용 부동산 계약을 다루는 법무 실사 담당자입니다.\n" +
            "아래 <REVIEW> 는 한 계약서를 이미 검토한 결과입니다" +
            "(리스크 조항·근거 원문·누락 보호장치·미기재 공란 포함).\n" +
            "이 검토 결과를 바탕으로, 문제 조항마다 **실제로 계약서에 넣을 수 있는 수정 문구**를 작성한 " +
            "'조항별 수정안(레드라인)'을 만드세요.\n" +
            "계약서 전문을 다시 쓰지 마세요 - 검토가 지목한 조항만 고쳐 쓰고, 나머지는 건드리지 않습니다.\n\n" +

            UnderwritingPrompts.COMMON_STRICT_RULES + "\n" +

            "## 작성 원칙\n" +
            "- 검토 관점: **${perspective.label}**.\n" +
            "- revisions 는 <REVIEW> 의 riskAssessment(및 조항 수정으로 해결되는 blanks/inconsistencies)에서 도출한다.\n" +
            "- original(현행)은 검토의 evidence(원문 인용)를 그대로 쓴다. evidence 가 없으면 해당 조항의 요지를 간결히 적고, 지어내지 않는다.\n" +
            "- revised(수정)는 **추상적 조언이 아니라 그대로 삽입 가능한 한국어 계약 조항 문체**로 작성한다(예: '~한다', '~로 한다').\n" +
            "- additions 는 missingProtections 를 신설 조항 초안(표준 조항 수준)으로. 초안임을 감안해 과도한 특약은 지양.\n" +
            "- blanksToFill 는 blanks 를 무엇을 채워야 하는지 안내로.\n" +
            "- 검토 관점에 유리하게 수정하되, 상대방이 받아들일 수 있는 통상적 범위를 넘지 않는다.\n\n" +

            "## 응답 규칙\n" +
            "- JSON 객체 하나만 출력. 코드펜스·설명·주석 금지.\n" +
            "- 해당 사항이 없는 배열은 빈 배열로 둔다. 억지로 채우지 마라.\n\n" +

            "## 출력 형식\n" +
            REVISE_SHAPE + "\n\n" +

            "## 검토 자료\n" +
            "<REVIEW>\n" + reviewJson + "\n</REVIEW>"

    /** 같은 딜에 묶인 계약 2~4건의 **문서 사이 관계**를 심사한다(개별 조항 재검토 아님). */
    fun compareSet(documentsJson: String): String =
        "당신은 한 부동산 거래(딜)에 묶인 여러 계약서를 함께 심사하는 법무 실사 책임자입니다.\n" +
            "아래 <DOCUMENTS> 는 같은 거래에 속한 계약서(예: 매매·도급·신탁·임대차 등) 각각을 이미 개별 검토한 요약(JSON 배열)입니다.\n" +
            "개별 조항을 다시 검토하지 말고, **문서들 사이의 관계**를 심사하세요: 당사자·금액·일정이 서로 맞는지, " +
            "한 문서의 조건이 다른 문서와 충돌하거나 한쪽에만 걸려 있는지, 거래 전체 구조에 어떤 리스크가 있는지.\n\n" +

            UnderwritingPrompts.COMMON_STRICT_RULES + "\n" +

            "## 심사 원칙\n" +
            "- <DOCUMENTS> 에 주어진 값만 근거로 사용. 없는 당사자·금액·일자는 창작하지 말고 모르면 언급하지 않는다.\n" +
            "- consistency 는 **둘 이상의 문서를 실제로 대조해** 발견한 불일치·충돌만 담는다. " +
            "단일 문서 내부 이슈는 제외(그건 개별 검토 몫).\n" +
            "- linkage 는 문서 간 조건부 연결(예: 매매 잔금 지급이 도급 준공을 조건으로 함)과, " +
            "그 연결이 한쪽 문서에만 규정돼 있어 생기는 공백을 본다.\n" +
            "- docsInvolved 에는 관련 문서의 docName 을 그대로 적는다.\n" +
            "- severity 는 거래를 깨뜨릴 수 있는 정도로 판단(HIGH|MEDIUM|LOW).\n\n" +

            "## 응답 규칙\n" +
            "- JSON 객체 하나만 출력. 코드펜스·설명·주석 금지.\n" +
            "- 해당 사항이 없는 배열은 빈 배열로 둔다. 억지로 채우지 마라.\n\n" +

            "## 출력 형식\n" +
            SET_COMPARE_SHAPE + "\n\n" +

            "<DOCUMENTS>\n" + documentsJson + "\n</DOCUMENTS>"

    /**
     * 예시 상호는 실제 회사명을 쓰지 않는다 - 모델이 예시 상호를 결과로 그대로 베끼는 일이 있어
     * 존재하지 않는 당사자가 검토 결과에 등장할 수 있다.
     */
    private const val REVIEW_SHAPE = """{
  "contractTitle": "부동산 매매 계약서",
  "contractType": "매매",
  "assetType": "오피스",
  "parties": [
    {"role": "갑(매도인)", "name": "주식회사 가나", "businessNumber": "123-45-67890"},
    {"role": "을(매수인)", "name": "주식회사 다라", "businessNumber": null}
  ],
  "contractDate": "2026-01-15", "effectiveDate": "2026-02-01", "expiryDate": null,
  "totalAmount": "120억원",
  "paymentTerms": "계약금 10% 계약 시, 중도금 40% 60일 내, 잔금 50% 소유권 이전 시",
  "autoRenewal": null,
  "terminationConditions": ["중도금 미지급 시 최고 없이 해제"],
  "penalties": "계약금 몰취 (손해배상 예정)",
  "specialTerms": ["현상태 인도(as-is)", "하자담보책임 6개월로 단축"],
  "disputeResolution": "서울중앙지방법원 전속 관할",
  "keyObligations": ["갑: 소유권 이전, 제한물권 말소", "을: 대금 적기 지급"],
  "summary": "120억원 규모 오피스 매매 계약으로 ...",
  "riskAssessment": [
    {"clause":"하자담보책임 단축","severity":"HIGH","issue":"민법상 담보책임을 6개월로 단축해 매수인 보호가 크게 약화됨","evidence":"제9조 '하자담보책임은 잔금일로부터 6개월로 한다'","recommendation":"최소 1년으로 연장하거나 중대하자는 예외로 존치"},
    {"clause":"무최고 해제","severity":"MEDIUM","issue":"중도금 1회 지연만으로 최고 없이 해제·계약금 몰취 가능","evidence":"제7조","recommendation":"상당기간 최고 절차 삽입"}
  ],
  "missingProtections": ["손해배상액 상한", "권리·행정 하자에 대한 매도인 진술보장", "매수인 채무불이행 대비 연대보증"],
  "negotiationPoints": ["하자담보 기간 6개월→1년", "무최고 해제 조항에 최고 절차 추가"],
  "blanks": ["제3조 - 계약일자 [*]월 [*]일 미기재", "제5조 제(2)항 - 지연이자율 [*]% 공란"],
  "inconsistencies": ["제7조 - 항번호 (2) 중복", "제9조 ↔ 제4조 - 잔금일 정의가 서로 상충"],
  "overallRisk": "MEDIUM",
  "reviewOpinion": "핵심 지급 구조는 통상적이나 하자담보 단축과 무최고 해제가 매수인에게 불리하다. 두 조항 수정 전제 하에 체결 권고."
}"""

    private const val REVISE_SHAPE = """{
  "title": "계약서 수정안",
  "perspective": "매수인",
  "revisions": [
    {"ref":"제9조 (하자담보책임)","severity":"HIGH","issue":"담보책임 6개월로 단축 - 매수인 보호 약화","original":"하자담보책임은 잔금일로부터 6개월로 한다","revised":"하자담보책임은 잔금일로부터 1년으로 하며, 중대한 하자에 대하여는 민법이 정한 바에 따른다","rationale":"민법상 담보책임 대비 과도한 단축 방지"}
  ],
  "additions": [
    {"ref":"신설 (손해배상 상한)","clauseText":"양 당사자의 본 계약 관련 손해배상 책임의 총액은 계약금액을 한도로 한다. 다만 고의 또는 중대한 과실의 경우에는 그러하지 아니하다.","rationale":"무한책임 노출 차단"}
  ],
  "blanksToFill": [ {"ref":"제3조 계약일","note":"계약 체결일자가 공란 - 기재 필요"} ],
  "summary": "총 N개 조항 수정 + M개 신설 제안. 핵심은 하자담보 단축과 무최고 해제 조항의 보완이다."
}"""

    private const val SET_COMPARE_SHAPE = """{
  "dealSummary": "이 거래 전체를 2~3문장으로. 어떤 문서들이 어떻게 엮여 하나의 거래를 이루는지",
  "overallRisk": "HIGH|MEDIUM|LOW",
  "documents": [
    {"docName":"파일/계약명","role":"이 거래에서의 역할(예: 자산 매입 본계약)","contractType":"매매|도급|신탁|임대차|기타","parties":"주요 당사자","totalAmount":"계약 금액","keyDates":"핵심 일자(계약/잔금/준공 등)","oneLineSummary":"한 줄 요약"}
  ],
  "consistency": [
    {"topic":"당사자|금액|일정|관할|위약|하자담보 등","severity":"HIGH|MEDIUM|LOW","finding":"어느 문서와 어느 문서가 어떻게 어긋나는지 구체적으로","docsInvolved":["A","B"],"recommendation":"체결 전 조치"}
  ],
  "linkage": [
    {"structure":"문서 간 조건부 연결 구조 설명","risk":"그 연결에서 오는 리스크","missingLink":"한쪽에만 걸려 있거나 빠진 연결고리","recommendation":"조치"}
  ],
  "dealRisks": ["거래 전체 관점의 리스크 ..."],
  "checklist": ["체결 전 확인해야 할 것 ..."]
}"""
}
