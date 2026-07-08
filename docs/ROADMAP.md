# ROADMAP - aixnative

> 상태 범례: ✅ 완료(라이브) · 🟡 부분/진행 · ⬜ 예정

## Phase 0 - 결정·관문  ✅
- 빌드툴/베이스 패키지 확정: Gradle Kotlin DSL + `com.aixnative`.
- 프론트: React 19 + Vite SPA(모바일 반응형).
- 도메인/클라우드/DB(PostgreSQL) 확정.

## Phase 1 - 파운데이션  ✅
- Kotlin + Spring Boot 3.5 스캐폴딩, Spring Data JPA + Flyway(단일 폴더), H2(dev)/PostgreSQL(운영).
- 멀티테넌트 계정/인증: 회원가입·이메일 인증·비번 재설정 + JWT + 소셜(구글·카카오·네이버).
- 모든 엔티티 `tenant_id` + `owner_user_id`, 테넌트 스코프 강제(IDOR 차단).
- 크레딧 원장(`credit_ledger`) + plan(FREE/PAID), 가입 무료 크레딧(이메일 인증 후).
- AI 라우터 이식(`AiServiceManager` + Claude/Mistral) + 미터링.

## Phase 2 - hero: AI 딜 언더라이팅  ✅
- `ProFormaCalculator`(IRR·멀티플·DSCR·민감도)·CRE 가이드라인·프롬프트 이식.
- 입력 폼 -> ProForma(무료) -> AI 분석 클릭 -> 크레딧 차감 -> Claude 내러티브·스크리닝·리스크 -> `AiToolRun` 저장.
- 퍼널: 가입 -> 무료 N회 -> 분석 -> 0이면 페이월.

## Phase 3 - 프리티어 UX  ✅
- 무료 잔여 크레딧 표시, 페이월 화면, 사용 내역(`CreditHistoryView`), 내 딜 대시보드(`MyDealsView`).

## Phase 4 - 인접 기능 이식  ✅
- 심화 분석 10종(BOV·개발타당성·가격예측·거래상대방 실사·세무진단·건물 리서치·입력가이드·자산관리 분기·보유매각재융자·심화 시장리서치).
- 실측 시장데이터 grounding(ECOS·R-ONE·RTMS·V-World·juso·공공데이터).
- 시장 자동수집(RSS·구글뉴스 결정론 딜카드 + 무료 Mistral 브리핑), 헤드라인, 보고서 export/공유.
- 자산관리(BETA): 건물·임대차·임대료 캘린더·만기 리마인더.

## Phase 5 - 결제  🟡
- 토스페이먼츠 크레딧 충전(서버 승인검증 + 멱등) 라이브(테스트 모드). ✅
- 라이브 전환(사업자등록·PG 심사 후 라이브키 교체), 영수증·환불 정식화. ⬜

## Phase 6 - 출시 하드닝  🟡
- 이용약관·개인정보처리방침·"투자자문 아님" 면책. ✅
- 오픈 계측(로그인 추적·`user_event` 퍼널·관리자 다이제스트). ✅
- 가입 레이트리밋·이메일/소셜 인증 어뷰징 방어. ✅
- 모니터링·백업 정식화(Cloud SQL 자동 백업·알림), 부하/비용 튜닝(min-instances). ⬜

## 다음 과제 후보
- 결제 라이브키 전환 + 환불/영수증 흐름 정식화(Phase 5 마무리).
- 관리자 콘솔 사용자별 이벤트 필터(현재 무필터 top-200).
- 언더라이팅 화면에도 딜 rename-all 적용(현재 심화분석만).
- 실측 API 상업 약관 확인(data.go.kr·OpenDART·ECOS·R-ONE 유료 노출 허용 여부) -> [API-KEYS.md](API-KEYS.md).
- 모니터링/백업 하드닝, 콜드스타트 대응.
