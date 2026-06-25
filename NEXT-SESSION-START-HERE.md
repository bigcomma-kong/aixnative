# 여기서 시작하세요 (다음 세션)

이 디렉터리는 **underwrite-ai** — MASTERN 투자 기능의 상업판(AI 딜 언더라이팅 SaaS) 신규 프로젝트입니다.
아직 **코드는 없고 기획/핸드오프 문서만** 있습니다. 이전 세션에서 설계를 확정하고 기록만 남긴 상태입니다.

## 읽는 순서
1. `CLAUDE.md` — 프로젝트 정체성·확정 결정·보안/격리 원칙
2. `docs/ROADMAP.md` — Phase 0~6 단계
3. `docs/ARCHITECTURE.md` — 패키지·멀티테넌시·크레딧 게이트·인증 설계
4. `docs/PORT-MAP.md` — MASTERN 에서 이식할 코드 vs 새로 짤 코드
5. `docs/API-KEYS.md` — 발급할 외부 API 키

## 확정된 핵심
- **hero = AI 딜 언더라이팅** (입력→ProForma 지표 + AI 내러티브/스크리닝/리스크). "AI 분석 1클릭 = 1크레딧".
- 스택: **Kotlin + Spring Boot 3 + JPA/Flyway(PostgreSQL)**, JWT+소셜 인증, 멀티테넌트(`tenant_id`), 개인 격리, freemium(무료 N회→구매), 결제는 나중.

## 시작 전 남은 결정 2가지
1. **빌드툴/베이스 패키지** — Gradle Kotlin DSL + `com.underwriteai` 제안 → 확정?
2. **프론트 형태** — REST API + SPA(React/Vue) vs 서버템플릿, 모바일 반응형 범위

## 결정되면 → Phase 1 스캐폴딩 착수
Gradle Kotlin SB3 + Spring Data JPA + Flyway(PostgreSQL) + 멀티테넌트 계정·JWT 인증(+소셜) + `credit_ledger` + AI 라우터 이식(`AiServiceManager` + provider 클라이언트, **신규 키**). 이어서 언더라이팅 도메인 이식.

## hero 언더라이팅은 외부 데이터 의존이 거의 없다 (장점)
ProForma 계산은 **순수 로직**(외부 API 0), AI 내러티브는 **Claude 1개**면 충분 → **v1 최소 키 = Claude + 이메일(가입)**.
실거래가(comps)·금리(ECOS)는 **나중에 더하는 enrich** 라 초기 약관 리스크/연동 부담이 작다.

> ⚠ MASTERN 운영 레포·`C:\eclipse_MASTERN\` 보호구역은 **읽기 참조만**. 수정 금지.
