# PORT-MAP — MASTERN → aixnative

출처 루트: `C:\eGovFrame-4.9.5\eclipse-workspace\MASTERN\src\main\java\com\mastern\…` (읽기 전용 참조)

## A. 그대로 이식 (순수 로직, DB/Spring 무관) — 최우선 자산
| 클래스 | 위치 | 내용 | hero 관련 |
|---|---|---|---|
| `ProFormaCalculator` | util/ | 5년 프로포마(IRR·Equity Multiple·DSCR·민감도), BigDecimal 순수 | ★ 언더라이팅 코어 |
| `CreGuidelines` | util/ | CRE 임계값·스크리닝 플래그(KR 2026) 상수 | ★ 스크리닝 판정 |
| `AiPromptBuilder` | service/ | 60+ 프롬프트(스크리닝·언더라이팅·IC메모·BOV…). **프롬프트=핵심자산, verbatim 복사** | ★ UNDERWRITING_NARRATIVE |
| `WaterfallEngine` | service/ | 4-tier European 워터폴 | 펀드(후순위) |
| `LeaseFlagRuleEngine` / `LoiFlagRuleEngine` | service/ | 임대차/LOI 위험 규칙 엔진(Map 입력) | 인접 기능 |
| `ImV2ReportBuilder` / `ImDocHtmlBuilder` | util/ | 분석결과 JSON → HTML/DOCX(POI), 무상태 | 보고서 |

## B. 가벼운 재작성 (설정만 분리)
| 클래스 | 비고 |
|---|---|
| `AiServiceManager` | 우선순위·폴백·서킷·타임아웃. config 프로퍼티만 `application.yml`+env 로 이전 |
| `ClaudeService`(+Mistral/Cohere/Groq) | LLM HTTP 클라이언트. 포팅 후 **신규 키** |

## C. 영속 재작성 (서비스 로직 유지, DAO→JPA)
| 클래스 | 비고 |
|---|---|
| `AiToolRunService`(+DAO/VO) | AI 실행 이력/소프트삭제. tool allowlist 존재 |
| `AiCallMeter` / `RequestUsageInterceptor` / `ApiUsageStatsService` | AI 호출 미터링(ThreadLocal+async) |
| `LicenseService`/`LicenseFilter`/`QuotaService`(+LicenseKeyDao/BillingCycleDao) | **상업 골격 참고**. 키검증·쿼터·과금주기. Oracle MERGE→PG UPSERT |

## D. 셸 신규 (재사용 안 함)
- 모든 Controller = **REST**(JSP 버림), 멀티테넌트 계정/인증, 크레딧 원장, 프론트.

## hero(언더라이팅) 이식 묶음 — Phase 2 최소 세트
1. `ProFormaCalculator` (A) → Kotlin 포트(순수).
2. `CreGuidelines` (A) → 스크리닝 기준.
3. `AiPromptBuilder` 의 언더라이팅 프롬프트 (A) + `AiServiceManager`/`ClaudeService` (B).
4. `AiToolRunService` (C) → 분석 결과 영속(테넌트 격리).
참조 서비스(로직 패턴): MASTERN `UnderwritingService`, im-v2 언더라이팅 단계(`ImV2Controller`/`UnderwritingService`), 모달 자동채움 로직(im-v2.js openModal: NOI÷Cap 매입가 추정).

## 인접 기능 도메인 소스 (Phase 4 이후)
- 실거래가 comps: `TradeQueryService`/`TradeAnalysisService`.
- 시장 리서치: `MarketResearchService`.
- DART: `DartAnalysisService`/`DartService`.
- 펀드/LP: `FundService` + `FundMetricsService`/`WaterfallEngine`/`LpStatementService`.
- 자산운용: `OperationReportService`(PPTX)·`ImNoiService`·`TenantWatchlistService`·`LeaseContractReviewService`.

## 주의
- 이식 시 Oracle 전용 구문(SEQ.NEXTVAL/ROWNUM/SYSDATE/MERGE/||/NVL)은 JPA/Kotlin 으로 대체.
- `@Component`/Spring 의존은 떼어 순수 클래스로(테스트 용이).
- 프롬프트 문구는 **그대로** 옮길 것(검증된 자산).
