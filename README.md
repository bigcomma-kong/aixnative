# aixnative

AI 부동산 **딜 언더라이팅** SaaS (상업판). 사내 모놀리스의 투자 기능을 분리해 개인 가입형 freemium 서비스로 재구성한 신규 상업 프로젝트.

- **hero**: 입력(매입가·NOI·Cap·자본구조) → ProForma 지표(IRR·Equity Multiple·DSCR·민감도) + AI 언더라이팅 내러티브·스크리닝·리스크. "AI 분석 1클릭 = 1크레딧".
- **스택**: Kotlin + Spring Boot 3 · REST API · Spring Data JPA(Hibernate) + Flyway · PostgreSQL(dev) / Oracle(switchable)
- **모델**: 개인 계정 + 무료 N회 → 구매(freemium). 멀티테넌트(`tenant_id`), JWT 인증.
- **상태**: 기획 완료, 코드 착수 전. 문서는 `docs/` 및 `CLAUDE.md` 참조.

## 문서
| 파일 | 내용 |
|---|---|
| `CLAUDE.md` | 프로젝트 컨텍스트(세션 로드용) |
| `NEXT-SESSION-START-HERE.md` | 다음 작업 진입점 + 남은 결정 |
| `docs/ROADMAP.md` | Phase 0~6 |
| `docs/ARCHITECTURE.md` | 패키지·멀티테넌시·크레딧·인증 설계 |
| `docs/PORT-MAP.md` | 도메인/AI 로직 이식 맵 (내부 레거시 → aixnative) |
| `docs/API-KEYS.md` | 발급할 외부 API 키 |

## 원칙
- 검증된 도메인/AI 로직은 **이식**(내부 레거시), 셸·상업 배관은 **신규**.
- 외부 API 키는 **전부 신규 발급**(기존 값 복사 금지). 시크릿은 환경변수.
- 데이터는 테넌트 단위 격리(IDOR 차단).
