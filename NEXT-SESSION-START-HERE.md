# 여기서 시작하세요 (다음 세션)

이 디렉터리는 **aixnative** - AI 딜 언더라이팅 SaaS(상업판)입니다.
**이미 구축되어 라이브 운영 중**입니다: <https://www.aixnative.com> (GCP Cloud Run · 서울).
가입·인증(이메일+소셜)·크레딧 원장·결제 충전(토스)·언더라이팅·심화분석·시장 자동수집·자산관리(BETA)까지 end-to-end 가동합니다.

## 읽는 순서
1. [`CLAUDE.md`](CLAUDE.md) - 프로젝트 정체성·확정 결정·보안/격리 원칙
2. [`docs/OVERVIEW.md`](docs/OVERVIEW.md) - 메뉴별 기능·스택·외부 API·과금 모델 (제품 전체 그림)
3. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - 패키지·멀티테넌시·크레딧 게이트·AI 라우터
4. [`docs/ENV.md`](docs/ENV.md) - 환경변수·시크릿·백업/복구
5. [`docs/COMMANDS.md`](docs/COMMANDS.md) - 빌드·재배포 명령어
6. [`docs/ROADMAP.md`](docs/ROADMAP.md) - 진행 현황과 다음 과제

## 현재 상태 (한눈에)
- **백엔드**: Kotlin + Spring Boot 3.5, JPA/Flyway(V1~V21), 도메인 패키지 = `account`·`billing`·`ai`·`underwriting`·`marketfeed`·`headline`·`payment`·`lead`·`property`·`analytics`·`admin`·`integration.*`·`common`.
- **프론트**: React 19 + Vite SPA (`frontend/`), 상단 메뉴 = 시장·언더라이팅·심화분석·자산관리(BETA)·마이페이지·관리자.
- **인프라**: Cloud Run 단일 컨테이너 + Cloud SQL(PostgreSQL) + Secret Manager. 이미지는 Cloud Build.
- **결제**: 토스페이먼츠 크레딧 충전(테스트 모드 라이브). 라이브 전환 = 키만 교체.

## 작업 규칙 (엄수)
- **배포**: 사용자가 "배포"/"해" 라고 명시할 때만. `deploy/deploy.sh` 통짜 실행은 **금지**(시크릿 회전 -> 전체 로그아웃 위험). 코드만 바뀐 재배포는 **이미지 빌드 + `gcloud run deploy` 이미지 교체**로 env·시크릿 보존 -> [`docs/COMMANDS.md`](docs/COMMANDS.md) §5.
- **검증**: 배포 전 `./gradlew compileKotlin`(백엔드) + `cd frontend && npm run build`(프론트) 둘 다 그린 확인.
- **보안/격리**: MASTERN 레거시 레포는 읽기 전용 참조만. API 키 신규 발급. 테넌트 스코프 유지.

## 다음 과제 후보
자세한 현황·우선순위는 [`docs/ROADMAP.md`](docs/ROADMAP.md) 참조.

> ⚠ MASTERN 운영 레포·`C:\eclipse_MASTERN\` 보호구역은 **읽기 참조만**. 수정 금지.
