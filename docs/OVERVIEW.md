# aixnative — 제품 개요 (메뉴 · 스택 · API)

> AI 부동산 딜 언더라이팅 SaaS (상업판). "숫자는 코드/실측, 서술은 AI" 원칙.
> 자세한 설계는 [ARCHITECTURE.md](ARCHITECTURE.md) · [ENV.md](ENV.md) · [PORT-MAP.md](PORT-MAP.md) 참고.

---

## 1. 메뉴별 기능

상단 네비게이션은 5개(관리자는 ADMIN 계정만 노출).

### 🟦 시장 (Market Intelligence) — `MarketFeedView`
시장을 한눈에 보고 관심 딜로 바로 진입하는 **허브**. 데이터는 스케줄러가 자동 수집(무료).
- **마켓 브리핑** (뉴스레터 강점): 무료 AI(Mistral)가 수집 기사 풀을 합성 → 헤드라인·전망·주요동향·워치리스트·리스크. 시장 탭 상단 히어로.
- **딜 카드 피드** (딜모니터링 강점): RSS·구글뉴스에서 모은 매각·우선협상 딜 카드. 자산유형/위치/출처 배지.
- **이 딜 분석하기**: 카드 원문 → 심화 분석(딜 추출)으로 한 클릭 진입.
- 관리자: **지금 수집**(즉시 실행) · 카드 직접 추가/삭제.

### 🟦 언더라이팅 (Underwriting) — `UnderwriteView`
hero 기능. 입력(매입가·NOI·Cap·자본구조) → **ProForma 결정론 계산** + AI 내러티브.
- **무료**: ProForma 지표(IRR·Equity Multiple·DSCR·민감도·가이드라인 적합성) — 코드 계산, AI·크레딧 미사용.
- **과금(1크레딧/클릭)**: 분석 단계 — `SCREENING`(스크리닝) · `MARKET_STUDY`(시장조사) · `UNDERWRITING`(언더라이팅 내러티브) · `IC_MEMO`(투자심의 메모).

### 🟦 심화 분석 (Advanced) — `DocAnalysisView`
문서/입력 기반 단계별 분석. 각 호출 = 1크레딧(성공 시). 숫자는 코드 확정(`calc`) + 서술은 AI.
| 타입 | 설명 |
|------|------|
| `PRICE_FORECAST` | 매입·매각 가격 예측(소득가치+실거래 comp 블렌딩, 신뢰도 배지) |
| `BOV` | 매각 BOV 3-Method 평가(Direct Cap·DCF·Sales Comp) |
| `DEV_FEASIBILITY` | 개발 타당성(수익률·마진·민감도, GO/CONDITIONAL/NO_GO) |
| `COUNTERPARTY_DD` | 거래상대방 실사(사업자상태·제재·기업정보·규모) |
| `TAX_PRICE_DIAGNOSIS` | 세무 가격적정성 진단 |
| `BUILDING_RESEARCH` | 건물/입지 리서치(IM) |
| `UNDERWRITING_GUIDE` | 가정값 추천 가이드 |
| `AM_QUARTERLY` | 자산관리 분기 리포트 |
| `HOLD_SELL_REFI` | 보유·매각·재융자 판단 |
| `MARKET_RESEARCH_DEEP` | 심화 시장 리서치 |

진입점 보조: **딜/기사 텍스트 → 구조화 추출**(무료, 폼 프리필).

### 🟦 사용 내역 (Credits) — `CreditHistoryView`
크레딧 잔액·적립/차감 원장(`credit_ledger`). 가입 무료 크레딧 → 소진 시 구매(freemium, 결제는 훅만).

### 🟦 관리자 (Admin) — `AdminView` *(ADMIN 전용)*
전체 사용자·분석 실행 모니터링 + 시장 피드 관리. `admin@aixnative.com` 가입 시 자동 ADMIN.

---

## 2. 기술 스택

### 백엔드
- **언어/런타임**: Kotlin · JVM 21
- **프레임워크**: Spring Boot 3.5 (Web · Security · Validation · Mail · Actuator)
- **빌드**: Gradle (Kotlin DSL)
- **영속**: Spring Data JPA (Hibernate) + **Flyway** 마이그레이션
- **DB**: **PostgreSQL 16**(운영, Cloud SQL) / **H2**(개발) — DB-agnostic SQL
- **인증**: Spring Security + **JWT**(jjwt 0.12.6) · BCrypt · 스테이트리스
- **멀티테넌시**: 모든 도메인 테이블 `tenant_id`(IDOR 차단, 현재 1유저=1테넌트)

### 프론트엔드
- **React 19** + **Vite 8** + **TypeScript**
- SPA — `/api`는 dev Vite 프록시, 운영은 Spring 단일 컨테이너가 정적 서빙
- 디자인: 라이트 핀테크(인디고), CSS 커스텀 프로퍼티 토큰

### 인프라 (GCP)
- **Cloud Run**(단일 컨테이너, min-instances=0) · **Cloud SQL**(PostgreSQL, db-f1-micro)
- **Secret Manager**(시크릿) · **Cloud Build**(이미지) · **Artifact Registry**(레지스트리)
- **Cloud Scheduler**(시장 자동수집 크론) · **Cloudflare**(aixnative.com DNS/프록시)
- 리전: `asia-northeast3`(서울)

---

## 3. 사용 외부 API

### 3.1 AI 제공자 (`com.aixnative.ai`)
| 제공자 | 용도 | API | 비용 | env |
|--------|------|-----|------|-----|
| **Anthropic Claude** | 과금 분석(언더라이팅·BOV·딜 추출 등) | Messages API (OAuth/`x-api-key`) | 크레딧 | `CLAUDE_OAUTH_TOKEN` / `CLAUDE_API_KEY` |
| **Mistral** | 시장 브리핑 합성(배치) | Chat Completions | **무료** | `MISTRAL_API_KEY` |

라우터(`AiServiceManager`): priority+fallback. Claude(0) 우선, Mistral(5)은 배치 전용으로 **직접** 호출(과금 격리).

### 3.2 실측 시장데이터 (`integration.marketdata`) — grounding
| 소스 | 클라이언트 | 용도 | env |
|------|-----------|------|-----|
| 한국은행 **ECOS** | `EcosClient` | 기준금리·국고채 | `ECOS_API_KEY` |
| 한국부동산원 **R-ONE** | `RebRoneClient` | 권역 공실률·임대료·수익률 | `REB_RONE_API_KEY` |
| 국토부 **RTMS** | `RtmsClient` | 상업·토지 실거래 comps | `DATA_GO_KR_API_KEY` |
| **V-World** | `VWorldClient` | 개별공시지가·용도지역(PNU) | `VWORLD_API_KEY` (+`VWORLD_DOMAIN`) |
| **도로명주소(juso)** | `JusoClient` | 주소→법정동코드+번지(PNU 조립 지오코더) | `JUSO_API_KEY` |

시군구코드(5자리)는 내장표 `LawdCode`. 미설정 소스는 graceful degrade(건너뜀).

### 3.3 거래상대방 실사 (`integration.bizhealth`) — `BizHealthClient`
입력 = 사업자등록번호. 전부 `DATA_GO_KR_API_KEY`(공공데이터):
- **NTS 사업자상태**(영업/휴업/폐업) · **조달청 부정당 제재** · **금융위 기업정보**(대표·설립·업종) · **국민연금 가입자 규모**

### 3.4 시장 자동수집 (`marketfeed.ingest`) — 키 0개
| 소스 | 용도 |
|------|------|
| **RSS**: 한국경제·매일경제·조선비즈(부동산) | 일반 부동산 동향 |
| **Google News RSS**(섹터 딜 검색) | 오피스·물류·호텔·리테일·데이터센터·리츠·PF/NPL 매각·우선협상 딜 |

수집은 결정론적(키·AI 불필요) → 딜 카드. 브리핑만 무료 Mistral 사용(graceful).

---

## 4. 비용/과금 모델 (2단)

| 구분 | 시점 | 엔진 | 비용 |
|------|------|------|------|
| 시장 데이터 수집 | 스케줄(배치, 평일 06:30 KST) | 결정론 매핑 + 무료 Mistral | **무료** |
| ProForma 계산 | 입력 즉시 | 순수 코드 | **무료** |
| AI 분석(언더라이팅·BOV·예측 등) | 사용자 클릭 | **Claude** | 1 크레딧/클릭 |

가입 시 무료 크레딧 지급 → 소진 시 구매(freemium).

---

## 5. 자동수집 파이프라인 트리거

Cloud Run `min-instances=0` → `@Scheduled` 대신 **Cloud Scheduler**가 토큰 보호 엔드포인트를 깨운다.

```
Cloud Scheduler (평일 06:30 KST)
  └─ POST /api/ingest/market-feed  (헤더 X-Ingest-Token)
       └─ RSS·구글뉴스 수집 → 정규화·중복제거·필터
            ├─ 딜 카드 적재(MarketFeedItem, 결정론·무료)
            └─ 마켓 브리핑 합성(MarketBriefing, 무료 Mistral·graceful)
```
관리자 수동: `POST /api/admin/market-feed/ingest`(JWT ADMIN) 또는 시장 탭 **"지금 수집"**.

---

## 6. 보안 원칙
- 🔐 MASTERN(사내 레거시) **API 키 값 절대 복사 금지** — 전부 신규 발급 + env 외부화.
- 하드코딩 시크릿 금지. 운영 시크릿은 Secret Manager, 로컬은 gitignore(`.idea/workspace.xml`).
- 모든 도메인 조회는 현재 테넌트로 스코프(IDOR 차단). 자동수집 트리거는 공유 토큰 검증.
- 면책: "투자자문 아님" 출력 하단 표기.
