# aixnative

**AI 부동산 딜 언더라이팅 SaaS** (상업판). 매입가·NOI·Cap·자본구조를 넣으면 ProForma 지표(IRR·Equity Multiple·DSCR·민감도)를 코드로 계산하고, AI가 언더라이팅 내러티브·스크리닝 판정·리스크 플래그를 붙인다. **"AI 분석 1클릭 = 1크레딧"** 이 과금 단위인 freemium 서비스.

> 원칙: **숫자는 코드/실측, 서술은 AI.** ProForma 계산은 순수 로직(외부 API 0), AI는 서술과 판정만 담당한다.

- **라이브**: <https://www.aixnative.com> (GCP Cloud Run · 서울 리전)
- **상태**: 운영 중. 가입·이메일 인증·소셜 로그인·크레딧 원장·결제 충전(토스)·시장 자동수집까지 end-to-end 가동.
- **정체성**: 사내 모놀리스(MASTERN)의 투자 기능을 떼어 만든 **독립 상업 제품**. 검증된 도메인/AI 로직은 이식, 셸·상업 배관은 신규.

## 핵심 기능 (상단 메뉴)

| 메뉴 | 화면 | 요약 |
|---|---|---|
| **시장** | `MarketView` / `MarketFeedView` | 무료 AI(Mistral) 마켓 브리핑 + RSS·구글뉴스 결정론 딜 카드 피드. 카드 -> "이 딜 분석" 한 클릭 진입 |
| **언더라이팅** | `UnderwriteView` | hero. ProForma 지표(무료) + AI 분석 단계(SCREENING·MARKET_STUDY·UNDERWRITING·IC_MEMO, 1크레딧/클릭) |
| **심화 분석** | `DocAnalysisView` | 문서/입력 기반 단계별 분석 10종(BOV·개발타당성·가격예측·거래상대방 실사·세무진단·입력가이드 등) |
| **자산관리** `BETA` | `PropertyView` | 건물/임대차 등록·임대료 캘린더·만기 리마인더 (관리자 노출) |
| **마이페이지** | `MyView` / `MyDealsView` | 내 딜 대시보드(딜 단위 집계), 크레딧 사용 내역, 이어서 분석 |
| **관리자** | `AdminView` | 사용자·분석 실행 모니터링, 오픈 계측 다이제스트, 시장 피드 관리 (ADMIN 전용) |

## 기술 스택

- **백엔드**: Kotlin 1.9 · Spring Boot 3.5 (Web·Security·Validation·Mail·OAuth2·Actuator) · JVM 21 · Gradle Kotlin DSL
- **영속**: Spring Data JPA(Hibernate) + **Flyway**(V1~V21, DB-agnostic) · PostgreSQL(운영/Cloud SQL) · H2(개발·테스트)
- **인증**: Spring Security + **JWT**(jjwt) · BCrypt · 소셜 OAuth(구글·카카오·네이버)
- **멀티테넌시**: 모든 도메인 테이블 `tenant_id` + `owner_user_id` (IDOR 차단, 현재 1유저=1테넌트)
- **프론트**: React 19 + Vite + TypeScript SPA (단일 컨테이너가 정적 서빙)
- **AI**: Anthropic Claude(과금 분석) + Mistral(무료 배치 브리핑), 라우터 `AiServiceManager`(priority + fallback)
- **인프라(GCP)**: Cloud Run(단일 컨테이너, min-instances=0) · Cloud SQL · Secret Manager · Cloud Build · Artifact Registry · Cloud Scheduler(자동수집) · Cloudflare(DNS)
- **결제**: 토스페이먼츠(크레딧 충전, 서버 승인검증 + 멱등)

## 빠른 시작 (로컬)

```bash
# 백엔드 (루트) - 기본 h2 프로필이라 무설치 부팅
./gradlew bootRun            # http://localhost:8080

# 프론트 (frontend/) - /api 는 8080 프록시
cd frontend && npm install && npm run dev   # http://localhost:5173
```

- 테스트: `./gradlew test` (H2 인메모리, ENV 없이 그린)
- 명령어 전체: [`docs/COMMANDS.md`](docs/COMMANDS.md)

## 문서 지도

| 파일 | 내용 |
|---|---|
| [`docs/OVERVIEW.md`](docs/OVERVIEW.md) | **제품 개요** - 메뉴별 기능·스택·사용 외부 API·과금 모델 (읽기 시작점) |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 패키지 구조·멀티테넌시·크레딧 게이트·AI 라우터 설계 |
| [`docs/ENV.md`](docs/ENV.md) | 환경변수·시크릿 관리·백업/복구·빌드 산출물 |
| [`docs/COMMANDS.md`](docs/COMMANDS.md) | 빌드·소스·안전 재배포 명령어 |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | 단계별 진행 현황(Phase 0~6)과 다음 과제 |
| [`docs/PORT-MAP.md`](docs/PORT-MAP.md) | 도메인/AI 로직 이식 맵 (내부 레거시 -> aixnative) |
| [`docs/API-KEYS.md`](docs/API-KEYS.md) | 외부 API 키 발급 목록(전부 신규 발급) |
| [`deploy/README.md`](deploy/README.md) | GCP 배포 가이드 |
| [`CLAUDE.md`](CLAUDE.md) | 프로젝트 컨텍스트·확정 결정·보안/격리 원칙 (세션 로드용) |

## 원칙 (엄수)

- **네이밍**: 제품 산출물 어디에도 "mastern" 을 쓰지 않는다. 제품 정체성 = aixnative. 'MASTERN' 은 개발 문서의 이식 출처로만 등장.
- **보안**: 기존 MASTERN API 키 값 복사 금지 -> 전부 신규 발급 + 환경변수. 하드코딩 시크릿·평문 비밀번호 금지. 모든 조회는 현재 테넌트로 스코프(IDOR 차단).
- **격리**: `C:\eclipse_MASTERN\`, `C:\eGovFrame-4.9.5\...\MASTERN` 은 읽기 전용 참조. 수정 금지.
- **면책**: "투자자문 아님" 을 출력 하단에 표기.
