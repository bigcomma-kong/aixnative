# ROADMAP — underwrite-ai

## Phase 0 — 결정·관문
- 빌드툴/베이스 패키지 확정(Gradle Kotlin DSL + `com.underwriteai`).
- 프론트 형태 결정(SPA vs 서버템플릿, 모바일 범위).
- 신규 API 키: **v1 최소 = Claude + 이메일(가입)**. (언더라이팅은 외부 데이터 거의 불필요.)
- 도메인/클라우드/DB(PostgreSQL) 확정.

## Phase 1 — 파운데이션 (hero 무관 공통 골격)
- Gradle **Kotlin + Spring Boot 3** 스캐폴딩.
- **Spring Data JPA + Flyway**(`db/migration` 단일 폴더), PostgreSQL.
- **멀티테넌트 계정/인증**: 회원가입·이메일 인증·비번재설정 + **JWT** + 소셜(구글/카카오).
- 모든 엔티티 `tenant_id` + `owner_user_id`. 테넌트 스코프 강제(IDOR 차단).
- **크레딧 원장**(`credit_ledger`) + `plan`(FREE/PAID). 가입 시 무료 N회 부여.
- **AI 라우터 이식**(`AiServiceManager` + provider 클라이언트, 신규 키) + 미터링.

## Phase 2 — hero: AI 딜 언더라이팅 (end-to-end)
- 도메인 이식: `ProFormaCalculator`(IRR·멀티플·DSCR·민감도), `CreGuidelines`(스크리닝 기준), `AiPromptBuilder`(UNDERWRITING_NARRATIVE/SCREENING).
- 입력 폼 → ProForma 계산(순수) → "AI 분석" 클릭 → **크레딧 차감** → Claude 내러티브·스크리닝·리스크 → 결과 저장(`AiToolRunService` 이식).
- 퍼널 완성: 가입 → 무료 N회 → 분석 → 0이면 페이월(402 stub).

## Phase 3 — 프리티어 UX
- 무료 잔여 횟수 표시, 페이월 화면(결제 전 stub), 사용 내역.

## Phase 4 — 인접 기능 점진 이식
- 실거래가 comps(`TradeQueryService`), 시장 리서치, DART, IM 보고서(PPTX) 등 — 언더라이팅을 보강하는 순서로.

## Phase 5 — 결제
- 포트원/토스 연동 → 결제 성공 웹훅 → 크레딧 충전 이벤트(`credit_ledger`). 영수증·환불 기본.

## Phase 6 — 출시 하드닝
- 이용약관·개인정보처리방침·"투자자문 아님" 면책.
- 어뷰징 방지(이메일/소셜 인증·계정당 원가 상한), 레이트리밋, 모니터링·백업.
- 모바일 프론트.

## 최소 출시 라인
가입·로그인 → 언더라이팅 1개(테넌트 격리) → 무료 크레딧 카운터 → 페이월 → (나중)결제·충전.
