# ARCHITECTURE — aixnative

## 패키지 (도메인/바운디드 컨텍스트별, flat 아님)
베이스 `com.aixnative`(제안):
- `account` — 가입·이메일인증·비번재설정·소셜로그인·테넌트·사용자
- `billing` — `credit_ledger`·plan(FREE/PAID)·크레딧 차감/충전·(나중)결제
- `ai` — AI 라우터(`AiServiceManager` 이식)·provider 클라이언트·프롬프트·tool-run 이력·미터링
- `underwriting` — hero: ProForma 계산·스크리닝·언더라이팅 분석 서비스/엔드포인트
- `integration.*` — 외부 API 클라이언트(claude, dart, trade, ecos, …) — 필요 시 점진
- `common` — 보안(JWT)·예외·공용 응답 envelope

## 멀티테넌시
- 모든 도메인 엔티티: `tenant_id`(필수) + `owner_user_id`.
- v1 = **1유저 = 1테넌트**. 추후 팀/회사 = 여러 유저 → 1테넌트로 무혈 확장.
- 강제: Hibernate `@Filter`(tenant_id) 또는 서비스 레이어에서 현재 테넌트 스코프. **모든 조회·수정은 현재 테넌트로 제한**(IDOR 차단). 회사 전체 공유(IS_SHARED) 개념 미도입.

## 인증 (JWT)
- Spring Security 6 + **stateless JWT**(access + refresh).
- 가입: 이메일/비번(+이메일 인증) 또는 **소셜 OAuth2(구글/카카오)** → 내부 JWT 발급.
- 테넌트/플랜/크레딧은 사용자 레코드 + `billing` 에서 조회.

## 크레딧 게이트 (과금 핵심)
- 단위: **AI 분석 버튼 1클릭 = 1크레딧**.
- AI 분석 엔드포인트 진입 시 AOP/인터셉터:
  1. 잔여 크레딧 확인(없으면 **402** + 페이월 안내).
  2. AI 호출 성공 시 `credit_ledger` 에 **차감 기록**(실패 시 차감 안 함).
- `credit_ledger`(append-only): 가입 무료부여(+N)·분석차감(-1)·(나중)결제충전(+M). 잔액 = 합계.
- `plan` FREE/PAID. 충전/구매는 Phase 5에서 이벤트만 추가.

## DB (DB-agnostic)
- **JPA(Hibernate) + Flyway**. 마이그레이션은 `src/main/resources/db/migration` **한 폴더**.
- Oracle 종속 SQL 금지 → ID 생성은 JPA(IDENTITY/SEQUENCE 추상화), 페이징·함수도 dialect 무관.
- dev=PostgreSQL, 운영 Oracle 필요 시 datasource+dialect 설정만 교체.

## AI 라우터 (이식)
- `AiServiceManager`: 우선순위 Claude(0)→Mistral(1)→Cohere(2)→Groq(3) 폴백 + 서킷브레이커 + 타임아웃.
- config 프로퍼티(`ai.service.*`, `*.api.key`)는 `application.yml` + env. **신규 키만.**
- `AiPromptBuilder`(언더라이팅 프롬프트) verbatim 이식.

## 면책·신뢰성
- 모든 분석 출력에 **"투자자문 아님"** 면책 + 출처/기준일/"추정" 표기.

## 프론트 (결정 대기)
- REST API 우선. SPA(React/Vue) 또는 서버템플릿 + 모바일 반응형. 별도 결정.
