# aixnative — AI 부동산 딜 언더라이팅 SaaS (상업판)

> 새 세션 시작 시 컨텍스트로 로드됩니다. **먼저 `NEXT-SESSION-START-HERE.md` 를 읽으세요.**

## 무엇인가
사내 모놀리스 **MASTERN**(`C:\eGovFrame-4.9.5\eclipse-workspace\MASTERN`, Spring Boot 3.3.5 / Java 21 / MyBatis / Oracle / JSP)의 **투자 기능**을 떼어 만든 **별도 상업용 SaaS**. 신규 독립 레포(이 디렉터리). 별도 클라우드 서버·DB.

**hero 기능 = AI 딜 언더라이팅** (프로젝트명 그대로). 입력(매입가·NOI·Cap·자본구조) → **ProForma 계산**(IRR·Equity Multiple·DSCR·민감도, Kotlin 순수 로직) + **AI 언더라이팅 내러티브·스크리닝 판정·리스크 플래그**(Claude). 이 "AI 분석 1클릭" 이 과금 단위.

**비즈니스 모델**: 개인 가입 → **무료 N회 AI 분석** → 소진 시 **구매**(freemium). 결제는 나중(설계 훅만).

**핵심 전략**: 셸·멀티테넌시·상업 배관은 **새로**, 검증된 도메인·AI 로직은 **PORT(이식)**. CRE 수식·프롬프트는 다시 만들지 않는다. → `docs/PORT-MAP.md`

## 확정된 결정 (변경 금지)
- **스택**: Kotlin + Spring Boot 3 (REST API) · 빌드 **Gradle Kotlin DSL**(권장)
- **영속**: Spring Data JPA(Hibernate) + Flyway. **DB 종속 SQL 금지**(Oracle SEQ.NEXTVAL/ROWNUM/SYSDATE/MERGE 안 씀)
- **DB**: DB-agnostic. dev 기본 **PostgreSQL(무료)**, dialect만 바꿔 Oracle 전환. 스키마는 **Flyway 한 폴더에 몰기**
- **"1회" = AI 분석 버튼 1클릭 = 1크레딧**
- **인증**: **JWT**, 이메일 + 소셜(구글/카카오) 가입
- **멀티테넌시**: 모든 테이블 **`tenant_id` day-1**. v1 = 1유저=1테넌트, 추후 팀/회사 플랜 확장 대비
- **면책**: "투자자문 아님" 하단/출력 표기
- **결제**: 나중에. 지금은 `credit_ledger` + `plan`(FREE/PAID) 필드만
- **배포**: 클라우드, 모바일 고려(REST API + 반응형/SPA)

## 네이밍 원칙 (엄수)
- **"MASTERN" / "Mastern AI Hub" 명칭을 완전히 버린다.** 제품명·브랜드·UI 문구·패키지·설정 키·이메일 발신명·도메인 어디에도 mastern 을 쓰지 않는다.
- 제품 정체성 = **aixnative**(독립 상업 제품). 사내 도구와 무관함을 외부에 드러낸다.
- 베이스 패키지 `com.aixnative`(또는 다른 중립 네임), **`com.mastern` 금지**.
- 'MASTERN' 은 **오직 개발 문서의 "코드 이식 출처"(내부 레거시)** 로만 등장한다. 제품 산출물엔 흔적 없음.

## 보안 원칙 (엄수)
- 🔐 **기존 MASTERN API 키 값 절대 복사 금지.** 전부 **신규 발급** + 환경변수(`${ENV:fallback}`). → `docs/API-KEYS.md`
- 하드코딩 시크릿·평문 비밀번호 금지 — 처음부터 env 외부화.
- 모든 조회는 **현재 테넌트로 스코프**(IDOR 차단). 회사 전체 공유 개념 미도입(개인 격리).

## 격리 (건드리면 안 됨)
- `C:\eclipse_MASTERN\` — 보호구역. 손대지 않음.
- `C:\eGovFrame-4.9.5\eclipse-workspace\MASTERN` — 운영 모놀리스. **읽기 전용 참조**(포팅 소스)만, 수정 금지.
- 작업은 오직 이 디렉터리 안에서.

## 다음 할 일
1. 빌드툴/베이스 패키지 확정(`com.aixnative` 제안) → Phase 1 스캐폴딩.
2. Phase 1: Kotlin SB3 + JPA/Flyway(PG) + 멀티테넌트 계정/인증(JWT+소셜) + 크레딧 원장 + AI 라우터 이식.
3. Phase 2: 언더라이팅 도메인 이식(ProForma·CreGuidelines·AiPromptBuilder) + 크레딧 게이트 end-to-end.

상세: `docs/ROADMAP.md` · `docs/ARCHITECTURE.md` · `docs/PORT-MAP.md` · `docs/API-KEYS.md`

## 컨벤션
- 응답은 **존댓말**.
- 패키지: 도메인/바운디드 컨텍스트별(`account`/`billing`/`ai`/`underwriting`/`integration.*`). flat 아님.
- 커밋/배포는 사용자가 명시할 때만.
