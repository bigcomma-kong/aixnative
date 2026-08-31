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

## Phase 7 - 유입 트랙(문서 미기재분 소급 반영)  🟡
- **동네 리포트**(`com.aixnative.residential`, V26) ✅ - 무인증 공개 무료 리포트(지오코딩·주변 POI·단지 스펙·아파트 실거래·거시). 랜딩 임베드. 유료 훅 = 동네 AI 브리핑(`PRESALE_BRIEF` 2크레딧).
- **공개 SEO 인사이트**(`PublicInsightsController` + `SeoController`) ✅ - `/insights` SSR, robots·sitemap.
- **공감랭킹 소셜 자동게시**(`com.aixnative.social`, V22~V25) 🟡 - 수집→Claude 각색→무료 AI 이미지→satori 렌더→관리자 승인→인스타. 코드·컨테이너 완비. **미배포**: `deploy.sh` 에 `SOCIAL_INGEST_TOKEN`·스케줄러 잡·`--no-cpu-throttling` 반영 완료, 실제 배포와 인스타 계정 연동이 남음.

## Phase 8 - 문서 업로드 트랙  ⬜
사내 레거시 벤치마킹 결과 최대 갭. 진행 계획은 세션 플랜 문서 참조.
- 8-A 문서 추출 인프라(`com.aixnative.document`) - PDF/DOCX/XLSX/PPTX/HWP → 텍스트. 무과금 `POST /api/documents/extract`. 기존 심화분석 10종이 코드 변경 없이 파일 입력을 얻는다.
- 8-B AI 계약서 검토(`com.aixnative.contract`) - 조항별 리스크·미기재 공란·조문 정합성·협상 포인트.
- 8-C 공고 분석(`com.aixnative.notice`) - 공매·매각·입찰 공고 정형 추출 + 2~4건 비교.

## 다음 과제 후보
- 결제 라이브키 전환 + 환불/영수증 흐름 정식화(Phase 5 마무리).
- 관리자 콘솔 사용자별 이벤트 필터(현재 무필터 top-200).
- 언더라이팅 화면에도 딜 rename-all 적용(현재 심화분석만).
- 실측 API 상업 약관 확인(data.go.kr·OpenDART·ECOS·R-ONE 유료 노출 허용 여부) -> [API-KEYS.md](API-KEYS.md).
- 모니터링/백업 하드닝, 콜드스타트 대응.
