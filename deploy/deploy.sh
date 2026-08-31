#!/usr/bin/env bash
# aixnative → GCP 배포 (Artifact Registry + Cloud Run + Cloud SQL + Secret Manager)
# 로컬 Docker 불필요 — 이미지는 Cloud Build 가 Dockerfile 로 빌드.
#
# 사용:
#   export PROJECT_ID=your-gcp-project
#   bash deploy/deploy.sh
#   (API 키는 application-secret.yml 에 넣어두면 이미지에 구워져 자동 반영 — 여기서 export 불필요)
#
# 멱등: 이미 있는 리소스는 건너뛰고, 시크릿/이미지/서비스는 새 버전으로 갱신.
set -euo pipefail

# 키 관리 = application-secret.yml 한 파일. Cloud Build 가 이미지에 구워넣고(.gcloudignore),
# 앱은 secret 프로필로 로드한다. → 배포는 키를 파라미터로 안 넘긴다.
# 여기 남는 시크릿은 자동생성 인프라(DB 비번·JWT·수집토큰)뿐 — 사용자가 만질 일 없음.

### ── 0. 설정 (env 로 override) ─────────────────────────────────────
PROJECT_ID="${PROJECT_ID:-}"                  # 필수: 본인 GCP 프로젝트 ID
REGION="${REGION:-asia-northeast3}"           # 서울
AR_REPO="${AR_REPO:-aixnative}"               # Artifact Registry 저장소
SERVICE="${SERVICE:-aixnative}"               # Cloud Run 서비스명
SQL_INSTANCE="${SQL_INSTANCE:-aixnative-pg}"  # Cloud SQL 인스턴스
DB_NAME="${DB_NAME:-aixnative}"
DB_USER="${DB_USER:-aixnative}"
SQL_TIER="${SQL_TIER:-db-f1-micro}"           # 최저가. 상용 트래픽 시 상향
IMAGE_TAG="${IMAGE_TAG:-v1}"
# 운영 도메인 + 현재 Cloud Run URL(도메인 매핑 전 브라우저 접속용). 도메인 연결 후엔 run.app 제거 가능.
ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-https://aixnative.com,https://www.aixnative.com,https://aixnative-310112011265.asia-northeast3.run.app,http://localhost:5173}"
# 이메일 인증 링크의 베이스 URL(도메인 연결 후엔 https://aixnative.com 로 교체).
APP_BASE_URL="${APP_BASE_URL:-https://aixnative-310112011265.asia-northeast3.run.app}"

# 인프라 시크릿(자동생성). 사용자 API 키는 여기 없음 — application-secret.yml 로 빌드에 구워짐.
DB_PASSWORD="${DB_PASSWORD:-}"
JWT_SECRET="${JWT_SECRET:-}"
# 시장 데이터 자동수집 트리거 토큰(Cloud Scheduler ↔ 앱 공유). 미설정 시 자동 생성.
MARKETFEED_INGEST_TOKEN="${MARKETFEED_INGEST_TOKEN:-}"
# 자동수집 크론(서울 시간). 기본 평일 06:30. Cloud Scheduler 잡으로 등록.
INGEST_CRON="${INGEST_CRON:-30 6 * * 1-5}"
# 공감랭킹 소셜 수집 트리거 토큰·크론. 시장 데이터와 별개 토큰/잡. 기본 매일 07:10.
SOCIAL_INGEST_TOKEN="${SOCIAL_INGEST_TOKEN:-}"
SOCIAL_CRON="${SOCIAL_CRON:-10 7 * * *}"

# SMTP(비-시크릿) — 호스트/계정/발신은 env, 비번(spring.mail.password)은 application-secret.yml 로 구워짐.
MAIL_HOST="${MAIL_HOST:-}"
MAIL_PORT="${MAIL_PORT:-587}"
MAIL_USERNAME="${MAIL_USERNAME:-}"
MAIL_FROM="${MAIL_FROM:-admin@aixnative.com}"
# 이 이메일로 가입 시 ADMIN 승격 — env 로 주입(안 주면 기본값).
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@aixnative.com}"

[ -z "$PROJECT_ID" ] && { echo "❌ PROJECT_ID 를 설정하세요: export PROJECT_ID=..."; exit 1; }
IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}/${SERVICE}:${IMAGE_TAG}"
ICN="${PROJECT_ID}:${REGION}:${SQL_INSTANCE}"    # instance connection name

echo "▶ project=$PROJECT_ID  region=$REGION"
echo "▶ image=$IMAGE"
gcloud config set project "$PROJECT_ID" >/dev/null

### ── 1. API 활성화 ────────────────────────────────────────────────
gcloud services enable \
  run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  sqladmin.googleapis.com secretmanager.googleapis.com

### ── 2. Artifact Registry ────────────────────────────────────────
gcloud artifacts repositories describe "$AR_REPO" --location="$REGION" >/dev/null 2>&1 || \
gcloud artifacts repositories create "$AR_REPO" \
  --repository-format=docker --location="$REGION" --description="aixnative images"

### ── 3. 이미지 빌드 (Cloud Build — 로컬 Docker 불필요) ────────────
gcloud builds submit --tag "$IMAGE" .

### ── 4. Cloud SQL (PostgreSQL 16) ────────────────────────────────
gcloud sql instances describe "$SQL_INSTANCE" >/dev/null 2>&1 || \
gcloud sql instances create "$SQL_INSTANCE" \
  --database-version=POSTGRES_16 --tier="$SQL_TIER" --region="$REGION" --storage-auto-increase

gcloud sql databases describe "$DB_NAME" --instance="$SQL_INSTANCE" >/dev/null 2>&1 || \
gcloud sql databases create "$DB_NAME" --instance="$SQL_INSTANCE"

# Cloud SQL 비밀번호 정책(대문자·소문자·숫자·특수문자 각 1+) 충족: 랜덤 본문 + 고정 보장 접미사.
[ -z "$DB_PASSWORD" ] && DB_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=')Aa1!"
if gcloud sql users describe "$DB_USER" --instance="$SQL_INSTANCE" >/dev/null 2>&1; then
  gcloud sql users set-password "$DB_USER" --instance="$SQL_INSTANCE" --password="$DB_PASSWORD"
else
  gcloud sql users create "$DB_USER" --instance="$SQL_INSTANCE" --password="$DB_PASSWORD"
fi

### ── 5. Secret Manager ───────────────────────────────────────────
[ -z "$JWT_SECRET" ] && JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
put_secret () {  # $1=name $2=value(빈 값이면 skip)
  local name="$1" val="$2"
  [ -z "$val" ] && return 0
  gcloud secrets describe "$name" >/dev/null 2>&1 || \
    gcloud secrets create "$name" --replication-policy=automatic
  printf '%s' "$val" | gcloud secrets versions add "$name" --data-file=-
}
# 자동수집 토큰 자동 생성(미지정 시) — 스케줄러와 앱이 공유. 시장·소셜은 별개 토큰.
[ -z "$MARKETFEED_INGEST_TOKEN" ] && MARKETFEED_INGEST_TOKEN="$(openssl rand -hex 24)"
[ -z "$SOCIAL_INGEST_TOKEN" ] && SOCIAL_INGEST_TOKEN="$(openssl rand -hex 24)"
# 자동생성 인프라 시크릿만 Secret Manager 로. 사용자 API 키는 이미지에 구워짐(application-secret.yml).
put_secret DB_PASSWORD             "$DB_PASSWORD"
put_secret JWT_SECRET              "$JWT_SECRET"
put_secret MARKETFEED_INGEST_TOKEN "$MARKETFEED_INGEST_TOKEN"
put_secret SOCIAL_INGEST_TOKEN     "$SOCIAL_INGEST_TOKEN"

### ── 6. Cloud Run 서비스 계정 권한 (Cloud SQL + 시크릿) ──────────
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
RUN_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
for role in roles/cloudsql.client roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${RUN_SA}" --role="$role" >/dev/null
done

### ── 7. Cloud Run 배포 ───────────────────────────────────────────
# Cloud SQL 소켓 팩토리 JDBC URL (build.gradle 의 postgres-socket-factory 사용)
DB_URL="jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${ICN}&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

# 자동생성 인프라 시크릿만 Cloud Run 에 매핑. 사용자 API 키는 이미지에 구워짐(secret 프로필로 로드).
SECRET_MAP="DB_PASSWORD=DB_PASSWORD:latest,JWT_SECRET=JWT_SECRET:latest"
gcloud secrets describe MARKETFEED_INGEST_TOKEN >/dev/null 2>&1 && SECRET_MAP="${SECRET_MAP},MARKETFEED_INGEST_TOKEN=MARKETFEED_INGEST_TOKEN:latest"
gcloud secrets describe SOCIAL_INGEST_TOKEN >/dev/null 2>&1 && SECRET_MAP="${SECRET_MAP},SOCIAL_INGEST_TOKEN=SOCIAL_INGEST_TOKEN:latest"

# env-vars 조립 ('|' 구분자 ^|^ — ALLOWED_ORIGINS 의 콤마 + MAIL_FROM 의 '@' 회피). 메일 호스트가 주어졌을 때만 SMTP env 추가.
ENV_VARS="SPRING_PROFILES_ACTIVE=postgres,secret|DB_URL=${DB_URL}|DB_USER=${DB_USER}|ALLOWED_ORIGINS=${ALLOWED_ORIGINS}|APP_BASE_URL=${APP_BASE_URL}|MAIL_FROM=${MAIL_FROM}|ADMIN_EMAIL=${ADMIN_EMAIL}"
if [ -n "$MAIL_HOST" ]; then
  ENV_VARS="${ENV_VARS}|SPRING_MAIL_HOST=${MAIL_HOST}|SPRING_MAIL_PORT=${MAIL_PORT}|SPRING_MAIL_USERNAME=${MAIL_USERNAME}|SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true|SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true"
fi

gcloud run deploy "$SERVICE" \
  --image="$IMAGE" --region="$REGION" --platform=managed \
  --allow-unauthenticated \
  --add-cloudsql-instances="$ICN" \
  --set-env-vars="^|^${ENV_VARS}" \
  --set-secrets="$SECRET_MAP" \
  --cpu=1 --memory=2Gi --min-instances=0 --max-instances=4 --port=8080 \
  --no-cpu-throttling
# --no-cpu-throttling: 응답을 반환한 뒤에도 백그라운드 작업(소셜 수집·이미지 재생성, AsyncIngestRunner)이
#   완주하려면 CPU 상시 할당이 필요하다. 기본(요청 중에만 CPU)이면 응답 직후 워커가 얼어붙는다.
# --memory=2Gi: 문서 업로드(PDFBox/POI)는 원본의 3~8배 힙을 쓴다. 1Gi(힙 약 768MB)에서는 동시 처리 시
#   OOMKilled 위험. 2Gi 로 여유를 두되 동시 추출은 서비스단 세마포어로 별도 제한한다.

URL="$(gcloud run services describe "$SERVICE" --region="$REGION" --format='value(status.url)')"

### ── 8. Cloud Scheduler (시장 데이터 자동 수집 트리거) ────────────
# min-instances=0 이라 @Scheduled 대신 스케줄러가 토큰 보호 엔드포인트를 깨워 수집을 돌린다.
gcloud services enable cloudscheduler.googleapis.com >/dev/null
SCHED_JOB="${SCHED_JOB:-aixnative-market-feed}"
INGEST_URI="${URL}/api/ingest/market-feed"
if gcloud scheduler jobs describe "$SCHED_JOB" --location="$REGION" >/dev/null 2>&1; then
  gcloud scheduler jobs update http "$SCHED_JOB" --location="$REGION" \
    --schedule="$INGEST_CRON" --time-zone="Asia/Seoul" \
    --uri="$INGEST_URI" --http-method=POST \
    --update-headers="X-Ingest-Token=${MARKETFEED_INGEST_TOKEN}" \
    --attempt-deadline=300s >/dev/null
else
  gcloud scheduler jobs create http "$SCHED_JOB" --location="$REGION" \
    --schedule="$INGEST_CRON" --time-zone="Asia/Seoul" \
    --uri="$INGEST_URI" --http-method=POST \
    --headers="X-Ingest-Token=${MARKETFEED_INGEST_TOKEN}" \
    --attempt-deadline=300s >/dev/null
fi

### ── 8b. Cloud Scheduler (공감랭킹 소셜 자동 수집 트리거) ──────────
# 시장 데이터와 같은 방식. 토큰은 별개(SOCIAL_INGEST_TOKEN)라 한쪽이 새도 다른 쪽은 안전하다.
# 수집 자체는 앱이 비동기로 돌리고 즉시 200 을 반환하므로 attempt-deadline 은 짧아도 된다.
SOCIAL_JOB="${SOCIAL_JOB:-aixnative-social-post}"
SOCIAL_URI="${URL}/api/ingest/social-post"
if gcloud scheduler jobs describe "$SOCIAL_JOB" --location="$REGION" >/dev/null 2>&1; then
  gcloud scheduler jobs update http "$SOCIAL_JOB" --location="$REGION" \
    --schedule="$SOCIAL_CRON" --time-zone="Asia/Seoul" \
    --uri="$SOCIAL_URI" --http-method=POST \
    --update-headers="X-Ingest-Token=${SOCIAL_INGEST_TOKEN}" \
    --attempt-deadline=60s >/dev/null
else
  gcloud scheduler jobs create http "$SOCIAL_JOB" --location="$REGION" \
    --schedule="$SOCIAL_CRON" --time-zone="Asia/Seoul" \
    --uri="$SOCIAL_URI" --http-method=POST \
    --headers="X-Ingest-Token=${SOCIAL_INGEST_TOKEN}" \
    --attempt-deadline=60s >/dev/null
fi

echo ""
echo "✅ 배포 완료: $URL"
echo "→ 첫 관리자: $URL 접속 후 admin@aixnative.com 으로 회원가입하면 자동 ADMIN 권한."
echo "→ 시장 자동수집: '${SCHED_JOB}' 스케줄러 등록(${INGEST_CRON}, Asia/Seoul). 관리자 '지금 수집'으로 즉시 실행 가능."
echo "→ 공감랭킹 수집: '${SOCIAL_JOB}' 스케줄러 등록(${SOCIAL_CRON}, Asia/Seoul). 승인 워크플로우는 관리자 콘솔에서."
echo "→ API 키(Claude·Mistral·Toss·소셜·시장데이터)는 application-secret.yml 값으로 이미지에 반영됨. 키 바꾸려면 그 파일 수정 후 재배포."
echo "→ 도메인 연결: deploy/README.md 의 '도메인 매핑' 참고."
