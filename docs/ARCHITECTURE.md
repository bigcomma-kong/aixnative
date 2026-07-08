# ARCHITECTURE - aixnative

> 실제 구현 기준(라이브). 제품 기능 전반은 [OVERVIEW.md](OVERVIEW.md), 환경/시크릿은 [ENV.md](ENV.md) 참조.

## 시스템 구성
```
브라우저 (React 19 SPA)
   │  /api  (same-origin)
   ▼
Cloud Run 단일 컨테이너 (Spring Boot 3.5, Kotlin/JVM21)
   ├─ Spring Security(JWT) · REST Controller
   ├─ 도메인 서비스 (테넌트 스코프)
   ├─ Flyway (기동 시 스키마 마이그레이션)
   ├─ AiServiceManager ──▶ Claude(과금) / Mistral(무료 배치)
   └─ integration.* ─────▶ 공공 실측 API (ECOS·R-ONE·RTMS·V-World·juso·공공데이터)
        │
        ▼ 소켓 팩토리
   Cloud SQL (PostgreSQL)
```
정적 프론트(`frontend/dist`)는 빌드 시 `static/` 으로 구워져 같은 컨테이너가 서빙(same-origin). 자동수집은 Cloud Scheduler 가 토큰 보호 엔드포인트를 깨운다.

## 패키지 (도메인/바운디드 컨텍스트별, flat 아님)
베이스 `com.aixnative`. 각 도메인은 4계층(`web`/`service`/`domain`/`repository`)으로 분리.

| 패키지 | 책임 |
|---|---|
| `account` | 가입·이메일 인증·비번 재설정·소셜 로그인(OAuth)·테넌트·사용자·역할 |
| `billing` | `credit_ledger`(append-only)·plan(FREE/PAID)·크레딧 차감/충전·게이트 |
| `ai` | AI 라우터(`AiServiceManager`)·provider 클라이언트·프롬프트·`AiToolRun` 이력·미터링 |
| `underwriting` | hero: ProForma 계산·스크리닝·언더라이팅/심화 분석 서비스·보고서(`ReportService`) |
| `marketfeed` | 시장 자동수집(RSS·구글뉴스)·마켓 브리핑(무료 Mistral)·딜 카드 |
| `headline` | 헤드라인 정제·집계 |
| `payment` | 토스페이먼츠 결제(승인검증·멱등·크레딧 충전 연동) |
| `lead` | 공개 SEO/리드 수집 |
| `property` | 자산관리(BETA): 건물·임대차·임대료 분석·만기 리마인더 |
| `analytics` | 오픈 계측(로그인 추적·`user_event` 퍼널·관리자 다이제스트) |
| `admin` | 운영 콘솔(사용자·실행 모니터링·시장 피드 관리) |
| `integration.marketdata` | ECOS·R-ONE·RTMS·V-World·juso 실측 클라이언트(grounding) |
| `integration.bizhealth` | 거래상대방 실사(사업자상태·제재·기업정보·연금) |
| `common` | 보안(JWT)·예외·공용 응답 envelope·설정(Flyway 등) |

## 멀티테넌시
- 모든 도메인 엔티티: `tenant_id`(필수) + `owner_user_id`.
- v1 = **1유저 = 1테넌트**. 추후 팀/회사 = 여러 유저 -> 1테넌트로 무혈 확장.
- 강제: 서비스 레이어에서 현재 테넌트(`TenantContext`)로 스코프. **모든 조회·수정은 현재 테넌트로 제한**(IDOR 차단). 회사 전체 공유(IS_SHARED) 개념 미도입.

## 인증 (JWT)
- Spring Security 6 + **stateless JWT**(jjwt, HS256) · BCrypt.
- 가입: 이메일/비번(+이메일 인증) 또는 **소셜 OAuth(구글·카카오·네이버)** -> 콜백에서 우리 JWT 발급(`#token=` 해시로 SPA 전달).
- 미설정 소셜 제공자 버튼은 자동 숨김(graceful). 테넌트/플랜/크레딧은 사용자 레코드 + `billing` 에서 조회.

## 크레딧 게이트 (과금 핵심)
- 단위: **AI 분석 버튼 1클릭 = 1크레딧**.
- AI 분석 진입 시: 잔여 크레딧 확인(없으면 페이월 안내) -> AI 호출 **성공 시에만** `credit_ledger` 차감 기록(실패 시 차감 안 함).
- `credit_ledger`(append-only): 가입 무료부여(+N, 이메일 인증 후)·분석차감(-1)·결제충전(+M). 잔액 = 합계.
- ProForma 계산·시장 자동수집은 **무료**(코드/결정론). Claude 호출만 과금.

## 딜 식별 (self-anchor)
- "딜" = `ai_tool_run.deal_id`(첫 런 id로 self-anchor). 별도 deal 테이블 없음.
- 딜 이름은 **수정 가능한 라벨**(딜 단위로 rename-all). 같은 이름이어도 deal_id 가 다르면 분리.
- 그룹핑/이어붙이기/보고서 합본은 전부 deal_id 기준(V21 backfill 로 기존 그룹 보존).

## DB (DB-agnostic)
- **JPA(Hibernate) + Flyway**. 마이그레이션은 `src/main/resources/db/migration` **한 폴더**(V1~V21).
- Oracle 종속 SQL 금지(SEQ.NEXTVAL/ROWNUM/SYSDATE/MERGE 안 씀) -> ID 생성은 JPA(IDENTITY), 페이징·함수도 dialect 무관.
- dev/test = H2, 운영 = PostgreSQL. Oracle 전환 필요 시 datasource + dialect 교체만.

## AI 라우터
- `AiServiceManager`: priority + fallback + 타임아웃/deadline. Claude(우선순위 0) 과금 분석, Mistral(무료)은 배치 브리핑 전용으로 직접 호출(과금 격리).
- config(`ai.*`, provider 키)는 `application.yml` + env. **신규 키만**. 인증은 `CLAUDE_OAUTH_TOKEN`(우선) 또는 `CLAUDE_API_KEY`.
- 프롬프트(`UnderwritingPrompts`·`PropertyPrompts` 등)는 검증된 자산 -> verbatim 유지.

## 면책·신뢰성
- 모든 분석 출력에 **"투자자문 아님"** 면책 + 출처/기준일/"추정" 표기.
- 실측 API 미설정 시 graceful degrade(AI 추정 모드 유지, 신뢰도 배지 하향).
