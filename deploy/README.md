v# aixnative — GCP 배포 가이드

**구조:** Cloud Run(단일 컨테이너: Spring + Vite SPA) + Cloud SQL(PostgreSQL) + Secret Manager + Artifact Registry.
이미지는 **Cloud Build** 가 빌드하므로 **로컬 Docker 불필요**.

---

## 0. 사전 준비 (1회)
1. **gcloud CLI 설치** 후 로그인:
   ```bash
   gcloud auth login
   gcloud auth application-default login
   ```
2. **GCP 프로젝트 + 결제 계정 연결**(콘솔에서). 프로젝트 ID 확보.
3. 리전 기본값 = `asia-northeast3`(서울). 바꾸려면 `export REGION=...`.

## 1. 배포 (한 방)
```bash
export PROJECT_ID=your-gcp-project
export CLAUDE_OAUTH_TOKEN=sk-ant-oat...      # 로컬 테스트와 동일 토큰 (또는 CLAUDE_API_KEY)
# (선택) export DB_PASSWORD=... JWT_SECRET=...  # 미설정 시 스크립트가 자동 생성
bash deploy/deploy.sh
```
스크립트가 순서대로: API 활성화 → Artifact Registry → **Cloud Build 이미지 빌드** → Cloud SQL 생성/DB/유저 → Secret Manager 등록 → Cloud Run SA 권한 → **Cloud Run 배포**. 끝나면 서비스 URL 출력.

## 2. 첫 로그인 (관리자)
prod(Postgres)에선 dev 시드(`@Profile("h2")`)가 **안 돕니다.** 대신 배포된 URL에서
**`admin@aixnative.com` 으로 회원가입**하면 `AuthService` 가 자동으로 ADMIN 권한 부여.
(관리자 이메일은 `app.admin-email` = `ADMIN_EMAIL` env 로 변경 가능.)

## 3. 도메인 매핑 (aixnative.com)
```bash
# 도메인 소유권 확인(최초 1회): https://search.google.com/search-console 에서 도메인 인증
gcloud run domain-mappings create --service=aixnative \
  --domain=aixnative.com --region=asia-northeast3
gcloud run domain-mappings describe --domain=aixnative.com --region=asia-northeast3
```
출력된 DNS 레코드(A/AAAA 또는 CNAME)를 도메인 등록기관에 등록 → 자동 HTTPS 발급.

---

## DB 연결 방식
Cloud Run → Cloud SQL 은 **소켓 팩토리**(`build.gradle.kts` 의 `postgres-socket-factory`)로 연결:
```
DB_URL=jdbc:postgresql:///aixnative?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory
```
스키마는 앱 기동 시 **Flyway** 가 자동 생성(첫 배포 시 V1·V2 적용).

## 프로필/시크릿 요약
| 항목 | 값/출처 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `postgres` (h2 기본 프로필 해제 → base = Postgres) |
| `DB_PASSWORD` `JWT_SECRET` | Secret Manager (`--set-secrets`) |
| `CLAUDE_OAUTH_TOKEN` / `CLAUDE_API_KEY` | Secret Manager. 없으면 AI 분석만 503 |
| `ALLOWED_ORIGINS` | 운영 도메인 (env) |

## ⚠ 상업 전환 체크리스트
- **AI 키 교체:** `CLAUDE_OAUTH_TOKEN`(구독, 테스트용) → **`CLAUDE_API_KEY`(Anthropic 종량)**. 코드 변경 없이 시크릿만 교체.
- **콜드스타트:** 기본 `--min-instances=0`(0까지 스케일다운, 비용↓ 대신 첫 요청 ~10s). 트래픽 생기면 `--min-instances=1`.
- **결제·레이트리밋·모니터링·백업:** Phase 5~6 (Cloud SQL 자동 백업 활성화, Cloud Logging/Monitoring, 약관/개인정보/면책).

## 비용 메모 (초기)
- Cloud Run: 0까지 스케일다운 → 유휴 시 거의 0.
- Cloud SQL `db-f1-micro`: 상시 과금(소액). 비용 최소화하려면 사용 안 할 때 인스턴스 stop 가능.
- Artifact Registry/Secret Manager/Cloud Build: 소액 또는 무료 한도 내.
