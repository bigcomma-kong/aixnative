#!/usr/bin/env bash
# aixnative → GCP 배포 (Artifact Registry + Cloud Run + Cloud SQL + Secret Manager)
# 로컬 Docker 불필요 — 이미지는 Cloud Build 가 Dockerfile 로 빌드.
#
# 사용:
#   export PROJECT_ID=your-gcp-project
#   export CLAUDE_OAUTH_TOKEN=sk-ant-oat...   # (상업 전환 시 CLAUDE_API_KEY 로)
#   bash deploy/deploy.sh
#
# 멱등: 이미 있는 리소스는 건너뛰고, 시크릿/이미지/서비스는 새 버전으로 갱신.
set -euo pipefail

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

# 시크릿 — env 로 주입(미설정 시 자동 생성). 절대 깃 커밋 금지.
DB_PASSWORD="${DB_PASSWORD:-}"
JWT_SECRET="${JWT_SECRET:-}"
CLAUDE_OAUTH_TOKEN="${CLAUDE_OAUTH_TOKEN:-}"
CLAUDE_API_KEY="${CLAUDE_API_KEY:-}"

# SMTP(이메일 인증 발송) — 설정 시 인증 메일 실제 발송, 미설정 시 링크 로그 폴백.
# MAIL_PASSWORD 는 Secret Manager(SPRING_MAIL_PASSWORD)로, 나머지는 env 로 주입.
MAIL_HOST="${MAIL_HOST:-}"
MAIL_PORT="${MAIL_PORT:-587}"
MAIL_USERNAME="${MAIL_USERNAME:-}"
MAIL_PASSWORD="${MAIL_PASSWORD:-}"
MAIL_FROM="${MAIL_FROM:-no-reply@aixnative.com}"

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
put_secret DB_PASSWORD          "$DB_PASSWORD"
put_secret JWT_SECRET           "$JWT_SECRET"
put_secret CLAUDE_OAUTH_TOKEN   "$CLAUDE_OAUTH_TOKEN"
put_secret CLAUDE_API_KEY       "$CLAUDE_API_KEY"
put_secret SPRING_MAIL_PASSWORD "$MAIL_PASSWORD"

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

SECRET_MAP="DB_PASSWORD=DB_PASSWORD:latest,JWT_SECRET=JWT_SECRET:latest"
# Claude 시크릿은 Secret Manager 에 존재하면 매핑 유지 — env 로 토큰을 다시 안 줘도
# 재배포 시 Cloud Run 에서 누락되지 않도록(누락되면 AI 분석이 503).
gcloud secrets describe CLAUDE_OAUTH_TOKEN >/dev/null 2>&1 && SECRET_MAP="${SECRET_MAP},CLAUDE_OAUTH_TOKEN=CLAUDE_OAUTH_TOKEN:latest"
gcloud secrets describe CLAUDE_API_KEY     >/dev/null 2>&1 && SECRET_MAP="${SECRET_MAP},CLAUDE_API_KEY=CLAUDE_API_KEY:latest"
# SMTP 비밀번호 시크릿이 있으면 매핑(SMTP 미설정이면 메일은 로그 폴백).
gcloud secrets describe SPRING_MAIL_PASSWORD >/dev/null 2>&1 && SECRET_MAP="${SECRET_MAP},SPRING_MAIL_PASSWORD=SPRING_MAIL_PASSWORD:latest"

# env-vars 조립 ('|' 구분자 ^|^ — ALLOWED_ORIGINS 의 콤마 + MAIL_FROM 의 '@' 회피). 메일 호스트가 주어졌을 때만 SMTP env 추가.
ENV_VARS="SPRING_PROFILES_ACTIVE=postgres|DB_URL=${DB_URL}|DB_USER=${DB_USER}|ALLOWED_ORIGINS=${ALLOWED_ORIGINS}|APP_BASE_URL=${APP_BASE_URL}|MAIL_FROM=${MAIL_FROM}"
if [ -n "$MAIL_HOST" ]; then
  ENV_VARS="${ENV_VARS}|SPRING_MAIL_HOST=${MAIL_HOST}|SPRING_MAIL_PORT=${MAIL_PORT}|SPRING_MAIL_USERNAME=${MAIL_USERNAME}|SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true|SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true"
fi

gcloud run deploy "$SERVICE" \
  --image="$IMAGE" --region="$REGION" --platform=managed \
  --allow-unauthenticated \
  --add-cloudsql-instances="$ICN" \
  --set-env-vars="^|^${ENV_VARS}" \
  --set-secrets="$SECRET_MAP" \
  --cpu=1 --memory=1Gi --min-instances=0 --max-instances=4 --port=8080

URL="$(gcloud run services describe "$SERVICE" --region="$REGION" --format='value(status.url)')"
echo ""
echo "✅ 배포 완료: $URL"
echo "→ 첫 관리자: $URL 접속 후 admin@aixnative.com 으로 회원가입하면 자동 ADMIN 권한."
echo "→ 도메인 연결: deploy/README.md 의 '도메인 매핑' 참고."
